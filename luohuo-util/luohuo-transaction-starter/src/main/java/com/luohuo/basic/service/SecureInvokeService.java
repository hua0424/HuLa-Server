package com.luohuo.basic.service;

import cn.hutool.core.util.ReflectUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.luohuo.basic.utils.JsonUtils;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.luohuo.basic.dao.SecureInvokeRecordDao;
import com.luohuo.basic.domain.dto.SecureInvokeDTO;
import com.luohuo.basic.domain.entity.SecureInvokeRecord;
import com.luohuo.basic.jackson.JsonUtil;
import com.luohuo.basic.utils.ShutdownDrainClock;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Description: 安全执行处理器
 * Date: 2023-08-20
 */
@Slf4j
@AllArgsConstructor
public class SecureInvokeService {

    public static final double RETRY_INTERVAL_MINUTES = 2D;

    private final SecureInvokeRecordDao secureInvokeRecordDao;

    private final Executor executor;

    /** #486 T5：执行资源拒绝次数（未执行、未计一次尝试），供 D7 容量测量读取。 */
    private final AtomicLong admissionDeferred = new AtomicLong();

    @Scheduled(cron = "*/5 * * * * ?")
    public void retry() {
        List<SecureInvokeRecord> secureInvokeRecords = secureInvokeRecordDao.getWaitRetryRecords();
        if (ShutdownDrainClock.isDraining()) {
            // #461：排空期不再新启重放。记录保持待重试状态，下次启动继续重放；
            // 调度器通常已在 SmartLifecycle 阶段停止，此门拦截残余竞态调用。
            for (SecureInvokeRecord secureInvokeRecord : secureInvokeRecords) {
                ShutdownDrainClock.replaySkipped();
                log.warn("排空截止：跳过重放（未执行，记录保留待下次启动重放）: id={}", secureInvokeRecord.getId());
            }
            return;
        }
        for (SecureInvokeRecord secureInvokeRecord : secureInvokeRecords) {
            doAsyncInvoke(secureInvokeRecord);
        }
    }

    public void save(SecureInvokeRecord record) {
        if (!secureInvokeRecordDao.save(record)) {
            throw new IllegalStateException("SecureInvoke intent was not persisted");
        }
    }

    private void retryRecord(SecureInvokeRecord record, String errorMsg) {
        Integer retryTimes = record.getRetryTimes() + 1;
        SecureInvokeRecord update = new SecureInvokeRecord();
        update.setId(record.getId());
        update.setFailReason(errorMsg);
        update.setNextRetryTime(getNextRetryTime(retryTimes));
        if (retryTimes > record.getMaxRetryTimes()) {
            update.setState(SecureInvokeRecord.STATUS_FAIL);
        } else {
            update.setRetryTimes(retryTimes);
        }
        secureInvokeRecordDao.updateById(update);
    }

    private LocalDateTime getNextRetryTime(Integer retryTimes) {//或者可以采用退避算法
        // Cap at 64 minutes: an extended MQ outage must not overflow the delay and strand committed sends.
        double waitMinutes = Math.pow(RETRY_INTERVAL_MINUTES, Math.min(retryTimes, 6));
        return LocalDateTime.now().plusMinutes((long) waitMinutes);
    }

    private void removeRecord(Long id) {
        if (!secureInvokeRecordDao.removeById(id)) {
            throw new IllegalStateException("SecureInvoke sent but intent removal failed: " + id);
        }
    }

    public void invoke(SecureInvokeRecord record, boolean async) {
        boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        //非事务状态，直接执行，不做任何保证。
        if (!inTransaction) {
            return;
        }
        //保存执行数据
        save(record);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @SneakyThrows
            @Override
            public void afterCommit() {
                //事务后执行
                if (ShutdownDrainClock.isDraining()) {
                    // #461：排空期不再新启重放；意图记录已持久化，下次启动由 retry() 重放
                    ShutdownDrainClock.replaySkipped();
                    log.warn("排空截止：事务提交后跳过重放（未执行，记录保留待下次启动重放）: id={}", record.getId());
                    return;
                }
                if (async) {
                    doAsyncInvoke(record);
                } else {
                    doInvoke(record);
                }
            }
        });
    }

    public void doAsyncInvoke(SecureInvokeRecord record) {
        if (ShutdownDrainClock.isDraining()) {
            // #461：doAsyncInvoke 是 retry()/afterCommit 之外的最后入口，再查一次截止
            ShutdownDrainClock.replaySkipped();
            log.warn("排空截止：跳过异步重放（未执行，记录保留待下次启动重放）: id={}", record.getId());
            return;
        }
        try {
            executor.execute(() -> {
				log.info("消息同步执行 线程：{}", Thread.currentThread().getName());
                doInvoke(record);
            });
        } catch (RejectedExecutionException refused) {
            // #486 T5：执行资源已满时的调度拒绝是一种独立结果——既不是一次网络尝试（不推进 retryTimes，
            // 不算进重试预算），也不能正常返回让调用方以为本次已执行完（记录必须保留待下次扫描）。
            // 记录仍是 WAIT 且 next_retry_time 未变，下一次 retry() 扫描会重新拿到它。
            admissionDeferred.incrementAndGet();
            log.warn("重放执行资源已满，本轮不派发（未执行，未计一次尝试，记录保留待下次扫描）: id={}, reason={}",
                    record.getId(), refused.getMessage());
        }
    }

    /**
     * #486 T5：执行资源拒绝累计次数（D7 容量测量用）。与真正执行过的尝试次数分开统计，
     * 避免把调度拒绝混进重试量。
     */
    public long admissionDeferredCount() {
        return admissionDeferred.get();
    }

    public void doInvoke(SecureInvokeRecord record) {
        SecureInvokeDTO secureInvokeDTO = record.getSecureInvokeDTO();
        // #461：在途重放允许跑完（不加执行门），仅计数供关停结算区分"结果不确定"
        ShutdownDrainClock.replayStarted();
        try {
            SecureInvokeHolder.setInvoking();
            Class<?> beanClass = Class.forName(secureInvokeDTO.getClassName());
            Object bean = SpringUtil.getBean(beanClass);
            List<String> parameterStrings = JsonUtils.toList(secureInvokeDTO.getParameterTypes(), String.class);
            List<Class<?>> parameterClasses = getParameters(parameterStrings);
            Method method = ReflectUtil.getMethod(beanClass, secureInvokeDTO.getMethodName(), parameterClasses.toArray(new Class[]{}));
            Object[] args = getArgs(secureInvokeDTO, parameterClasses);
            //执行方法
            method.invoke(bean, args);
            //执行成功更新状态
            removeRecord(record.getId());
        } catch (Throwable e) {
            log.error("SecureInvokeService invoke fail", e);
            //执行失败，等待下次执行
            retryRecord(record, e.getMessage());
        } finally {
            ShutdownDrainClock.replayFinished();
            SecureInvokeHolder.invoked();
        }
    }

    @NotNull
    private Object[] getArgs(SecureInvokeDTO secureInvokeDTO, List<Class<?>> parameterClasses) {
		JsonNode jsonNode = JsonUtil.toJsonNode(secureInvokeDTO.getArgs());
		Object[] args = new Object[jsonNode.size()];
        for (int i = 0; i < jsonNode.size(); i++) {
            Class<?> aClass = parameterClasses.get(i);
            args[i] = JsonUtil.nodeToValue(jsonNode.get(i), aClass);
        }
        return args;
    }

    @NotNull
    private List<Class<?>> getParameters(List<String> parameterStrings) {
        return parameterStrings.stream().map(name -> {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                log.error("SecureInvokeService class not fund", e);
            }
            return null;
        }).collect(Collectors.toList());
    }
}
