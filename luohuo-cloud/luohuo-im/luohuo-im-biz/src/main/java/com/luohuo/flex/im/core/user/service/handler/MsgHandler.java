package com.luohuo.flex.im.core.user.service.handler;

import com.luohuo.flex.im.core.chat.dao.WxMsgDao;
import com.luohuo.flex.im.domain.entity.WxMsg;
import jakarta.annotation.Resource;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author nyh
 */
@Component
public class MsgHandler extends AbstractHandler {

    @Resource
    private WxMsgDao wxMsgDao;

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage wxMessage,
                                    Map<String, Object> context, WxMpService weixinService,
                                    WxSessionManager sessionManager) {
        WxMsg msg = new WxMsg();
        msg.setOpenId(wxMessage.getFromUser());
        msg.setMsg(wxMessage.getContent());
        wxMsgDao.save(msg);
        return null;
    }

}
