# Graph Report - .  (2026-05-11)

## Corpus Check
- Large corpus: 2278 files · ~859,757 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder, or use --no-semantic to run AST-only.

## Summary
- 11403 nodes · 17474 edges · 647 communities detected
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 29 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## God Nodes (most connected - your core abstractions)
1. `StorageState` - 126 edges
2. `BaseRedis` - 104 edges
3. `TimeUtils` - 66 edges
4. `RouterMeta` - 60 edges
5. `ContextUtil` - 58 edges
6. `AiModelFactoryImpl` - 56 edges
7. `RedisOpsImpl` - 56 edges
8. `CaffeineOpsImpl` - 53 edges
9. `RoomAppServiceImpl` - 52 edges
10. `DateUtils` - 45 edges

## Surprising Connections (you probably didn't know these)
- `Email SMTP Configuration for Registration` --shows--> `HuLa-Server Instant Messaging System`  [AMBIGUOUS]
  docs/install/image/login.png → README.md
- `HuLa Client Login Interface Screenshot` --shows--> `HuLa 即时通讯客户端（Vue3+Vite7+Tauri）`  [INFERRED]
  preview/img_1.png → teamsdocs/_legacy/01-项目结构/目录盘点.md
- `HuLa Client Chat Interface Screenshot` --shows--> `HuLa 即时通讯客户端（Vue3+Vite7+Tauri）`  [INFERRED]
  preview/img_2.png → teamsdocs/_legacy/01-项目结构/目录盘点.md
- `HuLa Application Running Screenshot` --shows--> `HuLa 即时通讯客户端（Vue3+Vite7+Tauri）`  [INFERRED]
  preview/img_7.png → teamsdocs/_legacy/01-项目结构/目录盘点.md
- `HuLa IM Client Main Preview Screenshot` --shows--> `HuLa 即时通讯客户端（Vue3+Vite7+Tauri）`  [INFERRED]
  preview/img.png → teamsdocs/_legacy/01-项目结构/目录盘点.md

## Hyperedges (group relationships)
- **消息数据流路径（客户端→Gateway→WS-Server→RocketMQ→IM-Server→MySQL→RocketMQ→WS-Server→推送）** — luohuo_gateway, luohuo_ws, luohuo_im, rocketmq, rktmq_topics, external_middleware [EXTRACTED 1.00]
- **CI构建部署全流程** — ci_pipeline, container_build_model, docker_multistage, image_tag_convention, env_layering, integration_env [INFERRED 0.80]
- **REQ生命周期闭环** — req_lifecycle, collaboration_workflow, kanban, manager_role, reviewer_role, hula_docs [EXTRACTED 1.00]
- **aiclaw 三层架构：HuLa-Server → aichat-node → openclaw** — concept_hula_server, concept_aichat_node, concept_openclaw [EXTRACTED 1.00]
- **安全 prompt 注入链** — concept_safety_prompt, concept_public_persona, concept_relation_desc [EXTRACTED 1.00]
- **openclaw→aichat-plugins迁移决策链** — adr010_openclaw_core, adr010_adapter_boundary, adr013_replace_openclaw, adr013_aichatplugins_repo, adr001_openclaw, adr001_aichatplugins [EXTRACTED 1.00]
- **REQ-002 Core Architecture: HuLa-Server + aichat-plugins + openclaw** — concept_hula_server, aichat_plugins, concept_openclaw [EXTRACTED 1.00]
- **Non-Owner Security: Prompt Injection + Session Isolation + Public Persona** — concept_safety_prompt, concept_session_key, concept_public_persona [EXTRACTED 1.00]
- **4-Service Microservice Runtime Mesh** — luohuo_gateway, luohuo_oauth, luohuo_ws, luohuo_im [EXTRACTED 1.00]
- **Container Build + Test Tag + Manual Approval Release Workflow** — docker_container_build, ci_pipeline_shared, image_tag_convention_shared, release_checklist [EXTRACTED 1.00]
- **Hula Local Setup Infrastructure Prerequisites** — nacos, rocketmq, mysql_db, redis_cache, hula_local_setup [EXTRACTED 1.00]
- **Dual-Mode Architecture Module Strategy** — dual_mode_architecture, luohuo_im_facade, luohuo_base_facade [EXTRACTED 1.00]

## Communities

### Community 0 - "Core Utils & Base Classes"
Cohesion: 0.0
Nodes (446): AbstractGlobalResponseBodyAdvice, AccountGenerator, AllDataScopeProviderImpl, Appendix, AppendixMapper, AppendixResultVO, TypeFile, AppendixBizKey (+438 more)

### Community 1 - "IM Business Services"
Cohesion: 0.0
Nodes (293): AckConsumer, AdminChangeDTO, AdminSetReq, AdminStatsServiceImpl, Aiclaw, AiclawActivateReq, AiclawActivateResp, AiclawAuthConfirmReq (+285 more)

### Community 2 - "AI Chat Service"
Cohesion: 0.0
Nodes (180): AiApiKeyBalanceRespVO, BalanceInfo, AiApiKeyDO, AiApiKeyMapper, AiApiKeyPageReqVO, AiApiKeyRespVO, AiApiKeySaveReqVO, AiApiKeySimpleRespVO (+172 more)

### Community 3 - "FastDFS File Storage"
Cohesion: 0.01
Nodes (71): AbstractFastFileBuilder, AbstractFdfsCommand, AppendFileStorageClient, BytesUtil, CmdConstants, DefaultAppendFileStorageClient, DefaultConnection, DefaultTrackerClient (+63 more)

### Community 4 - "WebSocket & Push Service"
Cohesion: 0.01
Nodes (95): AckMessageDTO, AckProcessor, AiclawCryptoService, AllMutedVO, CallAcceptedVO, CallRejectedVO, CallRequestVO, CallReqVO (+87 more)

### Community 5 - "XXL-Job Task Scheduling"
Cohesion: 0.01
Nodes (49): AdminBiz, AdminBizClient, DirectoryListToolFunction, File, Request, Response, EmbedHttpServerHandler, EmbedServer (+41 more)

### Community 6 - "Message Extension Service"
Cohesion: 0.01
Nodes (86): AliSmsMsgStrategyImpl, AliSmsProperty, BaiduSmsMsgStrategyImpl, BaiduSmsProperty, BaseEventVO, BaseJob, BaseProperty, ClSendResult (+78 more)

### Community 7 - "Project Configuration"
Cohesion: 0.01
Nodes (68): AiServerApplication, ApiRequestFilter, AsyncProperties, AuthenticationSaInterceptor, BaseConfig, BaseServerApplication, BaseWebConfiguration, BootServerApplication (+60 more)

### Community 8 - "MyBatis & UID Generator"
Cohesion: 0.01
Nodes (34): BaseMybatisConfiguration, BitsAllocator, BufferPaddingExecutor, CachedUidGenerator, CacheId, DatabaseProperties, DefaultId, HutoolId (+26 more)

### Community 9 - "AI Integration"
Cohesion: 0.01
Nodes (34): AiAutoConfiguration, AiModelFactory, AudioModel, BaiChuanChatModel, DeepSeekChatModel, DouBaoChatModel, GeminiChatModel, AudioRequest (+26 more)

### Community 10 - "Tenant Management"
Cohesion: 0.02
Nodes (34): ContextConstants, Cache, CustomCacheProperties, FeignAddHeaderRequestInterceptor, GrayscaleLbConfig, GrayscaleLoadBalancer, GrayscaleLoadBalancerClientConfig, GrayscaleReactiveLoadBalancerClientFilter (+26 more)

### Community 11 - "Storage & Connection Pool"
Cohesion: 0.02
Nodes (1): StorageState

### Community 12 - "Login & Audit Log"
Cohesion: 0.02
Nodes (28): AddressUtil, BaseLoginLogController, DefLoginLog, DefLoginLogController, DefLoginLogManager, DefLoginLogManagerImpl, DefLoginLogMapper, DefLoginLogPageQuery (+20 more)

### Community 13 - "Redis Operations"
Cohesion: 0.03
Nodes (1): BaseRedis

### Community 14 - "System Monitoring"
Cohesion: 0.02
Nodes (8): ArithUtil, Cpu, DefServerController, Jvm, Mem, Server, Sys, SysFile

### Community 15 - "Community 15"
Cohesion: 0.03
Nodes (22): Config, ConfigMapper, EmailService, IceServer, IndexController, Init, MapController, MinioStorage (+14 more)

### Community 16 - "Community 16"
Cohesion: 0.03
Nodes (22): AbstractFrequencyControlService, Executor, SupplierThrowWithoutParam, FixedWindowDTO, FrequencyControlAspect, FrequencyControlConstant, FrequencyControlDTO, FrequencyControlException (+14 more)

### Community 17 - "Community 17"
Cohesion: 0.03
Nodes (7): Captcha, ChineseCaptcha, ChineseCaptchaAbstract, ChineseGifCaptcha, GifCaptcha, GifEncoder, SpecCaptcha

### Community 18 - "Community 18"
Cohesion: 0.03
Nodes (13): ConnectionPoolConfig, DefaultGenerateStorageClient, DefaultThumbImageConfig, FdfsClientConstants, FdfsCommand, FdfsConnectException, FdfsConnectionManager, FdfsException (+5 more)

### Community 19 - "Community 19"
Cohesion: 0.03
Nodes (17): BaseConstraintConverter, ConstraintInfo, DefaultConstraintExtractImpl, DigitsConstraintConverter, FieldValidatorDesc, FormValidatorController, HttpServletRequestValidatorWrapper, IConstraintConverter (+9 more)

### Community 20 - "Community 20"
Cohesion: 0.03
Nodes (18): LuohuoSentinelInvocationHandler, ScopeUtils, SentinelAutoConfiguration, SentinelFeignBuilder, Target, TargetController, TargetDao, TargetMapper (+10 more)

### Community 21 - "Community 21"
Cohesion: 0.07
Nodes (1): TimeUtils

### Community 22 - "Community 22"
Cohesion: 0.05
Nodes (12): AbstractRedisChannelMessage, AbstractRedisChannelMessageListener, AbstractRedisMessage, AbstractRedisStreamMessage, AbstractRedisStreamMessageListener, EarthRedisMQConsumerAutoConfiguration, EarthRedisMQProducerAutoConfiguration, NacosInstanceListener (+4 more)

### Community 23 - "Community 23"
Cohesion: 0.03
Nodes (1): RouterMeta

### Community 24 - "Community 24"
Cohesion: 0.07
Nodes (1): ContextUtil

### Community 25 - "Community 25"
Cohesion: 0.08
Nodes (1): AiModelFactoryImpl

### Community 26 - "Community 26"
Cohesion: 0.04
Nodes (1): RedisOpsImpl

### Community 27 - "Community 27"
Cohesion: 0.05
Nodes (1): CaffeineOpsImpl

### Community 28 - "Community 28"
Cohesion: 0.06
Nodes (1): RoomAppServiceImpl

### Community 29 - "Community 29"
Cohesion: 0.1
Nodes (1): DateUtils

### Community 30 - "Community 30"
Cohesion: 0.07
Nodes (8): Date2StringConverter, DateFormatRegister, LocalDate2StringConverter, LocalDateTime2StringConverter, LocalTime2StringConverter, Encoder, LuohuoFeignClientsRegistrar, OpenFeignAutoConfiguration

### Community 31 - "Community 31"
Cohesion: 0.06
Nodes (11): get(), match(), get(), match(), MySqlTypeConvert, OracleTypeConvert, get(), match() (+3 more)

### Community 32 - "Community 32"
Cohesion: 0.05
Nodes (1): CachePlusOps

### Community 33 - "Community 33"
Cohesion: 0.07
Nodes (1): XxlJobInfoVO

### Community 34 - "Community 34"
Cohesion: 0.05
Nodes (1): RoomAppService

### Community 35 - "Community 35"
Cohesion: 0.07
Nodes (35): aichat-plugins 通讯插件（Node.js）, AIChat（基于HuLa）企业级AI即时通讯系统, REQ-002 Aiclaw 新增接口, CI流水线与发布流程, 容器内构建模式（宿主机不安装构建工具链）, Docker多阶段构建（Builder+Runtime分离）, 三层分支模型（master/dev/feature/*）, HuLa-Admin 后台管理端 (+27 more)

### Community 36 - "Community 36"
Cohesion: 0.08
Nodes (3): JsonUtils, ServletUtils, XunFeiPptApi

### Community 37 - "Community 37"
Cohesion: 0.08
Nodes (7): PrivacyController, PrivacyService, PrivacyServiceImpl, PrivacySettingReq, UserPrivacy, UserPrivacyDao, UserPrivacyMapper

### Community 38 - "Community 38"
Cohesion: 0.07
Nodes (7): XssAuthConfiguration, XssFilter, XssProperties, XssRequestWrapper, XssStringJsonDeserializer, XssStringJsonSerializer, XssUtils

### Community 39 - "Community 39"
Cohesion: 0.13
Nodes (1): DefGenTableServiceImpl

### Community 40 - "Community 40"
Cohesion: 0.09
Nodes (12): FileApi, FileApiFallback, FileDeleteBO, FileFacade, FileFacadeImpl, FileParamVO, FileResultVO, get() (+4 more)

### Community 41 - "Community 41"
Cohesion: 0.09
Nodes (9): IdRespVO, UserEmoji, UserEmojiController, UserEmojiDao, UserEmojiMapper, UserEmojiReq, UserEmojiResp, UserEmojiService (+1 more)

### Community 42 - "Community 42"
Cohesion: 0.1
Nodes (1): DefUserServiceImpl

### Community 43 - "Community 43"
Cohesion: 0.08
Nodes (1): MPJLambdaWrapperX

### Community 44 - "Community 44"
Cohesion: 0.11
Nodes (1): QueryWrap

### Community 45 - "Community 45"
Cohesion: 0.07
Nodes (1): RoomController

### Community 46 - "Community 46"
Cohesion: 0.1
Nodes (7): CityParser, CityParserImpl, DefArea, DefAreaManager, DefAreaManagerImpl, DefAreaMapper, SqlCityParserDecorator

### Community 47 - "Community 47"
Cohesion: 0.08
Nodes (1): CollectionUtils

### Community 48 - "Community 48"
Cohesion: 0.15
Nodes (1): AbstractGlobalExceptionHandler

### Community 49 - "Community 49"
Cohesion: 0.1
Nodes (8): DFAFilter, Word, IWordFactory, MyWordFactory, SensitiveWord, SensitiveWordConfig, SensitiveWordDao, SensitiveWordMapper

### Community 50 - "Community 50"
Cohesion: 0.11
Nodes (5): CommonResult, ErrorCode, GlobalErrorCodeConstants, ServiceException, ServiceExceptionUtil

### Community 51 - "Community 51"
Cohesion: 0.07
Nodes (2): AiApiKeyController, AiApiKeyService

### Community 52 - "Community 52"
Cohesion: 0.08
Nodes (1): GroupState

### Community 53 - "Community 53"
Cohesion: 0.14
Nodes (1): RoomMetadataService

### Community 54 - "Community 54"
Cohesion: 0.1
Nodes (1): RoomServiceImpl

### Community 55 - "Community 55"
Cohesion: 0.13
Nodes (1): AiModelServiceImpl

### Community 56 - "Community 56"
Cohesion: 0.09
Nodes (5): InfoFeignLoggerFactory, DisableValidationTrustManager, RestTemplateConfiguration, TrustAllHostnames, RestTemplateHeaderInterceptor

### Community 57 - "Community 57"
Cohesion: 0.08
Nodes (1): DefUserService

### Community 58 - "Community 58"
Cohesion: 0.08
Nodes (1): AiModelService

### Community 59 - "Community 59"
Cohesion: 0.16
Nodes (2): EnumDeserializer, EnumSerializer

### Community 60 - "Community 60"
Cohesion: 0.09
Nodes (1): RoomService

### Community 61 - "Community 61"
Cohesion: 0.08
Nodes (3): DefTenantAnyoneController, DefTenantController, DefTenantService

### Community 62 - "Community 62"
Cohesion: 0.21
Nodes (1): WebFluxGlobalExceptionHandler

### Community 63 - "Community 63"
Cohesion: 0.17
Nodes (1): SuperServiceImpl

### Community 64 - "Community 64"
Cohesion: 0.15
Nodes (1): LbQueryWrap

### Community 65 - "Community 65"
Cohesion: 0.1
Nodes (1): UserServiceImpl

### Community 66 - "Community 66"
Cohesion: 0.14
Nodes (1): AiImageServiceImpl

### Community 67 - "Community 67"
Cohesion: 0.14
Nodes (1): LbUpdateWrap

### Community 68 - "Community 68"
Cohesion: 0.18
Nodes (1): PackageUtils

### Community 69 - "Community 69"
Cohesion: 0.17
Nodes (1): ChatServiceImpl

### Community 70 - "Community 70"
Cohesion: 0.1
Nodes (1): UserService

### Community 71 - "Community 71"
Cohesion: 0.2
Nodes (1): AiclawServiceImpl

### Community 72 - "Community 72"
Cohesion: 0.1
Nodes (1): UserController

### Community 73 - "Community 73"
Cohesion: 0.2
Nodes (1): AiChatMessageServiceImpl

### Community 74 - "Community 74"
Cohesion: 0.16
Nodes (2): LuohuoMdcAdapter, LuohuoMdcAdapterInitializer

### Community 75 - "Community 75"
Cohesion: 0.11
Nodes (1): ArgumentAssert

### Community 76 - "Community 76"
Cohesion: 0.09
Nodes (3): DefApplicationController, DefApplicationService, TenantAnyoneController

### Community 77 - "Community 77"
Cohesion: 0.18
Nodes (1): AiApiKeyServiceImpl

### Community 78 - "Community 78"
Cohesion: 0.19
Nodes (1): SuperCacheManagerImpl

### Community 79 - "Community 79"
Cohesion: 0.14
Nodes (1): XxlJobExecutor

### Community 80 - "Community 80"
Cohesion: 0.1
Nodes (20): ADR-20260309 Dockerized Dev-Test-Release Flow, Base Images (4-tier), CI/CD Pipeline (Container Build + Push + Manual Approval), Docker Containerized Build (no host toolchain), .env Layering (common/dev/staging/prod), Image Tag Convention (test-<shortsha> / vX.Y.Z), Jenkins Maven Install Cloud Module Build Output, Jenkins Gitee Project URL Configuration (+12 more)

### Community 81 - "Community 81"
Cohesion: 0.18
Nodes (1): SessionManager

### Community 82 - "Community 82"
Cohesion: 0.13
Nodes (1): DefUserController

### Community 83 - "Community 83"
Cohesion: 0.2
Nodes (1): DefResourceServiceImpl

### Community 84 - "Community 84"
Cohesion: 0.22
Nodes (1): AiKnowledgeSegmentServiceImpl

### Community 85 - "Community 85"
Cohesion: 0.17
Nodes (1): AiVideoServiceImpl

### Community 86 - "Community 86"
Cohesion: 0.17
Nodes (1): VideoChatService

### Community 87 - "Community 87"
Cohesion: 0.12
Nodes (1): GroupMemberDao

### Community 88 - "Community 88"
Cohesion: 0.13
Nodes (7): ActiveUserResp, AdminStatsController, AdminStatsResp, AiStats, BlackStats, AdminStatsService, LoginRankResp

### Community 89 - "Community 89"
Cohesion: 0.15
Nodes (1): DefUserManagerImpl

### Community 90 - "Community 90"
Cohesion: 0.12
Nodes (4): BaseOrgBO, DataScopeMapper, DataScopeService, DefResourceDataScope

### Community 91 - "Community 91"
Cohesion: 0.12
Nodes (1): AiclawService

### Community 92 - "Community 92"
Cohesion: 0.12
Nodes (1): FeedController

### Community 93 - "Community 93"
Cohesion: 0.13
Nodes (1): RootController

### Community 94 - "Community 94"
Cohesion: 0.19
Nodes (1): AiChatRoleServiceImpl

### Community 95 - "Community 95"
Cohesion: 0.24
Nodes (1): EchoServiceImpl

### Community 96 - "Community 96"
Cohesion: 0.13
Nodes (1): ContactDao

### Community 97 - "Community 97"
Cohesion: 0.15
Nodes (1): FriendServiceImpl

### Community 98 - "Community 98"
Cohesion: 0.12
Nodes (1): AiclawController

### Community 99 - "Community 99"
Cohesion: 0.19
Nodes (1): BaseOrgServiceImpl

### Community 100 - "Community 100"
Cohesion: 0.15
Nodes (1): AiKnowledgeSegmentService

### Community 101 - "Community 101"
Cohesion: 0.15
Nodes (1): QueryWrapperX

### Community 102 - "Community 102"
Cohesion: 0.2
Nodes (1): NacosSessionRegistry

### Community 103 - "Community 103"
Cohesion: 0.13
Nodes (1): DefGenTableService

### Community 104 - "Community 104"
Cohesion: 0.29
Nodes (2): IPUtils, IPWithMask

### Community 105 - "Community 105"
Cohesion: 0.17
Nodes (3): ACFilter, ACTrie, MatchResult

### Community 106 - "Community 106"
Cohesion: 0.13
Nodes (1): UserFriendDao

### Community 107 - "Community 107"
Cohesion: 0.26
Nodes (1): DefaultFastFileStorageClient

### Community 108 - "Community 108"
Cohesion: 0.34
Nodes (1): SysLogAspect

### Community 109 - "Community 109"
Cohesion: 0.13
Nodes (4): IValidatable, LengthConstraintValidator, NotEmptyConstraintValidator, NotNullConstraintValidator

### Community 110 - "Community 110"
Cohesion: 0.19
Nodes (1): CollHelper

### Community 111 - "Community 111"
Cohesion: 0.13
Nodes (15): AIChat Enterprise AI IM Project (based on HuLa), Docker Compose Environment Deployment, HuLa Project Logo, HuLa-Server Instant Messaging System, Project Startup Execution Result, TURN STUN Trickle ICE Test Result, Email SMTP Configuration for Registration, WeChat Community Group QR Code (+7 more)

### Community 112 - "Community 112"
Cohesion: 0.25
Nodes (1): RoomTimeoutService

### Community 113 - "Community 113"
Cohesion: 0.14
Nodes (1): PresenceCacheKeyBuilder

### Community 114 - "Community 114"
Cohesion: 0.18
Nodes (1): MessageAdapter

### Community 115 - "Community 115"
Cohesion: 0.21
Nodes (1): BaseEmployeeServiceImpl

### Community 116 - "Community 116"
Cohesion: 0.14
Nodes (1): DefResourceService

### Community 117 - "Community 117"
Cohesion: 0.15
Nodes (1): AiChatRoleService

### Community 118 - "Community 118"
Cohesion: 0.14
Nodes (1): AiImageService

### Community 119 - "Community 119"
Cohesion: 0.15
Nodes (1): AiKnowledgeDocumentService

### Community 120 - "Community 120"
Cohesion: 0.16
Nodes (1): LambdaQueryWrapperX

### Community 121 - "Community 121"
Cohesion: 0.14
Nodes (1): SuperService

### Community 122 - "Community 122"
Cohesion: 0.3
Nodes (1): AssertUtil

### Community 123 - "Community 123"
Cohesion: 0.25
Nodes (1): RedisOps

### Community 124 - "Community 124"
Cohesion: 0.22
Nodes (1): Either

### Community 125 - "Community 125"
Cohesion: 0.29
Nodes (1): CacheKeyBuilder

### Community 126 - "Community 126"
Cohesion: 0.14
Nodes (14): Hula Project Local Development Setup, Nacos Configuration Management Console, YAML Configuration File Editing, IDEA Import luohuo-util Module Dialog, Maven Install luohuo-util to Local Repository, Maven Install luohuo-cloud to Local Repository, Nacos Configuration Import Screenshot, Nacos Configuration Detail View (+6 more)

### Community 127 - "Community 127"
Cohesion: 0.15
Nodes (1): DefGenTableController

### Community 128 - "Community 128"
Cohesion: 0.26
Nodes (1): FeedServiceImpl

### Community 129 - "Community 129"
Cohesion: 0.24
Nodes (1): DefApplicationServiceImpl

### Community 130 - "Community 130"
Cohesion: 0.24
Nodes (1): AiMusicServiceImpl

### Community 131 - "Community 131"
Cohesion: 0.23
Nodes (1): AiKnowledgeDocumentServiceImpl

### Community 132 - "Community 132"
Cohesion: 0.17
Nodes (2): ArithmeticCaptcha, ArithmeticCaptchaAbstract

### Community 133 - "Community 133"
Cohesion: 0.22
Nodes (1): RedisAutoConfigure

### Community 134 - "Community 134"
Cohesion: 0.15
Nodes (1): CacheOps

### Community 135 - "Community 135"
Cohesion: 0.24
Nodes (1): ObjectMetaData

### Community 136 - "Community 136"
Cohesion: 0.22
Nodes (1): StrHelper

### Community 137 - "Community 137"
Cohesion: 0.22
Nodes (1): R

### Community 138 - "Community 138"
Cohesion: 0.17
Nodes (1): ChatService

### Community 139 - "Community 139"
Cohesion: 0.17
Nodes (1): DefUserManager

### Community 140 - "Community 140"
Cohesion: 0.27
Nodes (1): DefAreaServiceImpl

### Community 141 - "Community 141"
Cohesion: 0.21
Nodes (1): BaseRoleServiceImpl

### Community 142 - "Community 142"
Cohesion: 0.17
Nodes (1): BaseOrgService

### Community 143 - "Community 143"
Cohesion: 0.24
Nodes (1): AiModelController

### Community 144 - "Community 144"
Cohesion: 0.17
Nodes (9): Candidate, Content, GeminiApi, GenerateRequest, GenerateResponse, GenerationConfig, InlineData, Part (+1 more)

### Community 145 - "Community 145"
Cohesion: 0.27
Nodes (1): Quant

### Community 146 - "Community 146"
Cohesion: 0.23
Nodes (1): ThumbImage

### Community 147 - "Community 147"
Cohesion: 0.21
Nodes (1): LuohuoTenantLineInnerInterceptor

### Community 148 - "Community 148"
Cohesion: 0.38
Nodes (1): StreamProcessor

### Community 149 - "Community 149"
Cohesion: 0.18
Nodes (1): FriendCacheKeyBuilder

### Community 150 - "Community 150"
Cohesion: 0.27
Nodes (1): AbstractMsgHandler

### Community 151 - "Community 151"
Cohesion: 0.18
Nodes (1): FriendService

### Community 152 - "Community 152"
Cohesion: 0.18
Nodes (1): WsAdapter

### Community 153 - "Community 153"
Cohesion: 0.29
Nodes (1): NoticeServiceImpl

### Community 154 - "Community 154"
Cohesion: 0.2
Nodes (1): BaseRoleController

### Community 155 - "Community 155"
Cohesion: 0.18
Nodes (1): DefResourceController

### Community 156 - "Community 156"
Cohesion: 0.16
Nodes (3): CaffeineDistributedLock, DefTenantApplicationRelManagerImpl, DeleteController

### Community 157 - "Community 157"
Cohesion: 0.18
Nodes (1): BaseEmployeeService

### Community 158 - "Community 158"
Cohesion: 0.18
Nodes (1): DefTenantServiceImpl

### Community 159 - "Community 159"
Cohesion: 0.2
Nodes (2): JsonExceptionHandler, ResponseContext

### Community 160 - "Community 160"
Cohesion: 0.25
Nodes (1): AiChatConversationServiceImpl

### Community 161 - "Community 161"
Cohesion: 0.2
Nodes (11): aiclaw（AI 助理用户）, B8 延迟注销定时任务（XXL-Job 24h）, 好友申请转发机制, im_aiclaw_friend_ext 表, im_aiclaw 表, owner（aiclaw 拥有者）, aichat-claw 插件架构设计, REQ-002 AIclaw 功能需求设计 (+3 more)

### Community 162 - "Community 162"
Cohesion: 0.18
Nodes (11): ADR-001 aiclaw Architecture Decisions (D1-D15), 华血 (Approver), Rationale for Keeping aichat-node Middle Layer, 老贾 (PM), Plugins as aiclaw WS Device, REQ-002 aiclaw AI Assistant (首期), aichat-claw Plugin Architecture Design, AIclaw Feature Requirement Design v1.2 (+3 more)

### Community 163 - "Community 163"
Cohesion: 0.44
Nodes (1): OutputFileUtils

### Community 164 - "Community 164"
Cohesion: 0.33
Nodes (1): EchoType

### Community 165 - "Community 165"
Cohesion: 0.2
Nodes (1): AppendixService

### Community 166 - "Community 166"
Cohesion: 0.31
Nodes (1): AbstractRedisStringCache

### Community 167 - "Community 167"
Cohesion: 0.27
Nodes (1): ChatController

### Community 168 - "Community 168"
Cohesion: 0.2
Nodes (1): ContactController

### Community 169 - "Community 169"
Cohesion: 0.18
Nodes (2): DefUserApi, DefUserApiFallback

### Community 170 - "Community 170"
Cohesion: 0.29
Nodes (1): BaseEmployeeBiz

### Community 171 - "Community 171"
Cohesion: 0.42
Nodes (1): ResourceBiz

### Community 172 - "Community 172"
Cohesion: 0.2
Nodes (1): AiChatConversationService

### Community 173 - "Community 173"
Cohesion: 0.29
Nodes (1): AiToolServiceImpl

### Community 174 - "Community 174"
Cohesion: 0.29
Nodes (1): AiAudioServiceImpl

### Community 175 - "Community 175"
Cohesion: 0.29
Nodes (2): AudioRequest, SiliconFlowAudioApi

### Community 176 - "Community 176"
Cohesion: 0.24
Nodes (1): BaseMapperX

### Community 177 - "Community 177"
Cohesion: 0.36
Nodes (1): MybatisEnumTypeHandler

### Community 178 - "Community 178"
Cohesion: 0.2
Nodes (1): ValidatorUtil

### Community 179 - "Community 179"
Cohesion: 0.25
Nodes (2): NettyServerConfig, ServletWebSocketHandlerAdapter

### Community 180 - "Community 180"
Cohesion: 0.22
Nodes (1): RoomAdminMetadataCacheKeyBuilder

### Community 181 - "Community 181"
Cohesion: 0.22
Nodes (1): CloseRoomCacheKeyBuilder

### Community 182 - "Community 182"
Cohesion: 0.22
Nodes (1): PassageMsgCacheKeyBuilder

### Community 183 - "Community 183"
Cohesion: 0.22
Nodes (1): FeedMediaRelCacheKeyBuilder

### Community 184 - "Community 184"
Cohesion: 0.22
Nodes (1): ConfigCacheKeyBuilder

### Community 185 - "Community 185"
Cohesion: 0.22
Nodes (1): FeedTargetRelCacheKeyBuilder

### Community 186 - "Community 186"
Cohesion: 0.22
Nodes (1): FeedCommentCacheKeyBuilder

### Community 187 - "Community 187"
Cohesion: 0.22
Nodes (1): FeedLikeCacheKeyBuilder

### Community 188 - "Community 188"
Cohesion: 0.22
Nodes (1): DefClientCacheKeyBuilder

### Community 189 - "Community 189"
Cohesion: 0.22
Nodes (1): DefUserCacheKeyBuilder

### Community 190 - "Community 190"
Cohesion: 0.22
Nodes (1): DefUserUserNameCacheKeyBuilder

### Community 191 - "Community 191"
Cohesion: 0.22
Nodes (1): DefUserIdCardCacheKeyBuilder

### Community 192 - "Community 192"
Cohesion: 0.22
Nodes (1): DefUserMobileCacheKeyBuilder

### Community 193 - "Community 193"
Cohesion: 0.22
Nodes (1): DefUserEmailCacheKeyBuilder

### Community 194 - "Community 194"
Cohesion: 0.22
Nodes (1): ResourceApiCacheKeyBuilder

### Community 195 - "Community 195"
Cohesion: 0.22
Nodes (1): ApplicationResourceCacheKeyBuilder

### Community 196 - "Community 196"
Cohesion: 0.22
Nodes (1): TenantResourceCacheKeyBuilder

### Community 197 - "Community 197"
Cohesion: 0.22
Nodes (1): ResourceCacheKeyBuilder

### Community 198 - "Community 198"
Cohesion: 0.22
Nodes (1): ResourceResourceApiCacheKeyBuilder

### Community 199 - "Community 199"
Cohesion: 0.22
Nodes (1): TenantCacheKeyBuilder

### Community 200 - "Community 200"
Cohesion: 0.22
Nodes (1): TenantApplicationCacheKeyBuilder

### Community 201 - "Community 201"
Cohesion: 0.22
Nodes (1): AllResourceApiCacheKeyBuilder

### Community 202 - "Community 202"
Cohesion: 0.22
Nodes (1): RoleResourceCacheKeyBuilder

### Community 203 - "Community 203"
Cohesion: 0.22
Nodes (1): RoleCacheKeyBuilder

### Community 204 - "Community 204"
Cohesion: 0.22
Nodes (1): DefEmployeeNameCacheKeyBuilder

### Community 205 - "Community 205"
Cohesion: 0.22
Nodes (1): EmployeeOrgCacheKeyBuilder

### Community 206 - "Community 206"
Cohesion: 0.22
Nodes (1): DefEmployeeMobileCacheKeyBuilder

### Community 207 - "Community 207"
Cohesion: 0.22
Nodes (1): EmployeeCacheKeyBuilder

### Community 208 - "Community 208"
Cohesion: 0.22
Nodes (1): EmployeeRoleCacheKeyBuilder

### Community 209 - "Community 209"
Cohesion: 0.22
Nodes (1): OrgRoleCacheKeyBuilder

### Community 210 - "Community 210"
Cohesion: 0.22
Nodes (1): IsTenantAdminCacheKeyBuilder

### Community 211 - "Community 211"
Cohesion: 0.28
Nodes (1): AppendixServiceImpl

### Community 212 - "Community 212"
Cohesion: 0.28
Nodes (1): NacosRouterService

### Community 213 - "Community 213"
Cohesion: 0.25
Nodes (1): SensitiveWordBs

### Community 214 - "Community 214"
Cohesion: 0.31
Nodes (1): AbstractUrlDiscover

### Community 215 - "Community 215"
Cohesion: 0.25
Nodes (1): AbstractLocalCache

### Community 216 - "Community 216"
Cohesion: 0.42
Nodes (1): AbstractMsgMarkStrategy

### Community 217 - "Community 217"
Cohesion: 0.25
Nodes (1): GroupMemberCache

### Community 218 - "Community 218"
Cohesion: 0.22
Nodes (1): RoomGroupCache

### Community 219 - "Community 219"
Cohesion: 0.22
Nodes (1): UserContactCacheKeyBuilder

### Community 220 - "Community 220"
Cohesion: 0.22
Nodes (1): FeedService

### Community 221 - "Community 221"
Cohesion: 0.28
Nodes (1): UserSummaryCache

### Community 222 - "Community 222"
Cohesion: 0.25
Nodes (3): FeedMedia, FeedMediaDao, FeedMediaMapper

### Community 223 - "Community 223"
Cohesion: 0.22
Nodes (1): UserDao

### Community 224 - "Community 224"
Cohesion: 0.22
Nodes (1): BaseEmployeeController

### Community 225 - "Community 225"
Cohesion: 0.28
Nodes (1): DefResourceManagerImpl

### Community 226 - "Community 226"
Cohesion: 0.22
Nodes (1): BaseRoleService

### Community 227 - "Community 227"
Cohesion: 0.39
Nodes (1): DefDictServiceImpl

### Community 228 - "Community 228"
Cohesion: 0.25
Nodes (1): QrCodeGranter

### Community 229 - "Community 229"
Cohesion: 0.28
Nodes (1): StrUtils

### Community 230 - "Community 230"
Cohesion: 0.39
Nodes (1): AiWriteServiceImpl

### Community 231 - "Community 231"
Cohesion: 0.36
Nodes (1): SuperExcelController

### Community 232 - "Community 232"
Cohesion: 0.22
Nodes (1): SuperCacheManager

### Community 233 - "Community 233"
Cohesion: 0.33
Nodes (1): CacheResult

### Community 234 - "Community 234"
Cohesion: 0.22
Nodes (1): TrackerAddressHolder

### Community 235 - "Community 235"
Cohesion: 0.39
Nodes (1): LuohuoMetaObjectHandler

### Community 236 - "Community 236"
Cohesion: 0.22
Nodes (1): FutureUtils

### Community 237 - "Community 237"
Cohesion: 0.22
Nodes (1): LogParam

### Community 238 - "Community 238"
Cohesion: 0.25
Nodes (1): RoomMetadataCacheKeyBuilder

### Community 239 - "Community 239"
Cohesion: 0.46
Nodes (1): SourceCodeUtils

### Community 240 - "Community 240"
Cohesion: 0.25
Nodes (1): Selector

### Community 241 - "Community 241"
Cohesion: 0.25
Nodes (1): GroupMembersKeyBuilder

### Community 242 - "Community 242"
Cohesion: 0.25
Nodes (1): OnlineGroupMembersKeyBuilder

### Community 243 - "Community 243"
Cohesion: 0.25
Nodes (1): UserGroupsKeyBuilder

### Community 244 - "Community 244"
Cohesion: 0.25
Nodes (1): OnlineUserGroupsKeyBuilder

### Community 245 - "Community 245"
Cohesion: 0.25
Nodes (1): WxMsgKeyBuilder

### Community 246 - "Community 246"
Cohesion: 0.25
Nodes (1): DictParameterKeyBuilder

### Community 247 - "Community 247"
Cohesion: 0.25
Nodes (1): DictCacheKeyBuilder

### Community 248 - "Community 248"
Cohesion: 0.25
Nodes (1): DefUserTenantCacheKeyBuilder

### Community 249 - "Community 249"
Cohesion: 0.25
Nodes (1): ApplicationCacheKeyBuilder

### Community 250 - "Community 250"
Cohesion: 0.25
Nodes (1): BaseDictCacheKeyBuilder

### Community 251 - "Community 251"
Cohesion: 0.25
Nodes (1): PositionCacheKeyBuilder

### Community 252 - "Community 252"
Cohesion: 0.25
Nodes (1): OrgCacheKeyBuilder

### Community 253 - "Community 253"
Cohesion: 0.36
Nodes (5): get(), getByCode(), getCodeInt(), match(), matchByCode()

### Community 254 - "Community 254"
Cohesion: 0.25
Nodes (1): UserQuery

### Community 255 - "Community 255"
Cohesion: 0.29
Nodes (1): UrlDiscover

### Community 256 - "Community 256"
Cohesion: 0.25
Nodes (1): TextMsgHandler

### Community 257 - "Community 257"
Cohesion: 0.25
Nodes (1): RoomAdapter

### Community 258 - "Community 258"
Cohesion: 0.25
Nodes (1): RoomGroupDao

### Community 259 - "Community 259"
Cohesion: 0.25
Nodes (1): NoticeService

### Community 260 - "Community 260"
Cohesion: 0.25
Nodes (1): UserBackpackDao

### Community 261 - "Community 261"
Cohesion: 0.29
Nodes (3): FeedTarget, FeedTargetDao, FeedTargetMapper

### Community 262 - "Community 262"
Cohesion: 0.25
Nodes (1): BaseOrgController

### Community 263 - "Community 263"
Cohesion: 0.32
Nodes (1): BaseRoleManagerImpl

### Community 264 - "Community 264"
Cohesion: 0.25
Nodes (1): QrCacheKeyBuilder

### Community 265 - "Community 265"
Cohesion: 0.25
Nodes (1): UserInfoController

### Community 266 - "Community 266"
Cohesion: 0.43
Nodes (1): AiMindMapServiceImpl

### Community 267 - "Community 267"
Cohesion: 0.32
Nodes (1): AiKnowledgeServiceImpl

### Community 268 - "Community 268"
Cohesion: 0.25
Nodes (3): Chunk, OpenAiCompatSseClient, OpenAiCompatStreamingStrategy

### Community 269 - "Community 269"
Cohesion: 0.25
Nodes (3): OpenAiCompatCallStrategy, OpenAiCompatClient, Result

### Community 270 - "Community 270"
Cohesion: 0.25
Nodes (3): Chunk, DeepSeekSseClient, DeepSeekStreamingStrategy

### Community 271 - "Community 271"
Cohesion: 0.46
Nodes (1): PageController

### Community 272 - "Community 272"
Cohesion: 0.36
Nodes (1): Wraps

### Community 273 - "Community 273"
Cohesion: 0.25
Nodes (1): SpringUtils

### Community 274 - "Community 274"
Cohesion: 0.29
Nodes (1): UserRoomsCacheKeyBuilder

### Community 275 - "Community 275"
Cohesion: 0.29
Nodes (1): VideoRoomsCacheKeyBuilder

### Community 276 - "Community 276"
Cohesion: 0.52
Nodes (1): GenUtils

### Community 277 - "Community 277"
Cohesion: 0.48
Nodes (1): FileInsertUtil

### Community 278 - "Community 278"
Cohesion: 0.52
Nodes (6): capitalFirst(), removePrefix(), removePrefixAndCamel(), removeSuffix(), removeSuffixAndCamel(), underlineToCamel()

### Community 279 - "Community 279"
Cohesion: 0.29
Nodes (1): ColumnType

### Community 280 - "Community 280"
Cohesion: 0.29
Nodes (1): DefGenProjectController

### Community 281 - "Community 281"
Cohesion: 0.29
Nodes (1): FriendStatusKeyBuilder

### Community 282 - "Community 282"
Cohesion: 0.29
Nodes (1): GlobalOnlineDevicesKeyBuilder

### Community 283 - "Community 283"
Cohesion: 0.29
Nodes (1): GlobalOnlineUsersKeyBuilder

### Community 284 - "Community 284"
Cohesion: 0.29
Nodes (1): CaptchaCacheKeyBuilder

### Community 285 - "Community 285"
Cohesion: 0.29
Nodes (1): BaseApi

### Community 286 - "Community 286"
Cohesion: 0.52
Nodes (1): OrgHelperService

### Community 287 - "Community 287"
Cohesion: 0.29
Nodes (1): NodeDevices

### Community 288 - "Community 288"
Cohesion: 0.29
Nodes (1): RecallMsgHandler

### Community 289 - "Community 289"
Cohesion: 0.29
Nodes (1): NoticeMsgHandler

### Community 290 - "Community 290"
Cohesion: 0.29
Nodes (1): BotMsgHandler

### Community 291 - "Community 291"
Cohesion: 0.33
Nodes (1): AudioCallMsgHandler

### Community 292 - "Community 292"
Cohesion: 0.33
Nodes (1): VideoCallMsgHandler

### Community 293 - "Community 293"
Cohesion: 0.38
Nodes (1): ChatAdapter

### Community 294 - "Community 294"
Cohesion: 0.29
Nodes (1): MessageDao

### Community 295 - "Community 295"
Cohesion: 0.29
Nodes (1): RoomFriendDao

### Community 296 - "Community 296"
Cohesion: 0.38
Nodes (1): FeedCommentServiceImpl

### Community 297 - "Community 297"
Cohesion: 0.62
Nodes (1): FeedNotifyServiceImpl

### Community 298 - "Community 298"
Cohesion: 0.29
Nodes (1): ItemCache

### Community 299 - "Community 299"
Cohesion: 0.29
Nodes (1): FriendController

### Community 300 - "Community 300"
Cohesion: 0.29
Nodes (1): BaseRoleManager

### Community 301 - "Community 301"
Cohesion: 0.29
Nodes (1): DefResourceManager

### Community 302 - "Community 302"
Cohesion: 0.33
Nodes (1): DefApplicationManagerImpl

### Community 303 - "Community 303"
Cohesion: 0.29
Nodes (2): AesUtil, DeviceFingerprintValidator

### Community 304 - "Community 304"
Cohesion: 0.43
Nodes (1): CaptchaServiceImpl

### Community 305 - "Community 305"
Cohesion: 0.33
Nodes (1): DictServiceImpl

### Community 306 - "Community 306"
Cohesion: 0.38
Nodes (1): AiChatRoleController

### Community 307 - "Community 307"
Cohesion: 0.33
Nodes (4): Request, Response, WeatherInfo, WeatherQueryToolFunction

### Community 308 - "Community 308"
Cohesion: 0.43
Nodes (1): SiliconFlowImageModel

### Community 309 - "Community 309"
Cohesion: 0.43
Nodes (1): CircularList

### Community 310 - "Community 310"
Cohesion: 0.29
Nodes (1): Connection

### Community 311 - "Community 311"
Cohesion: 0.29
Nodes (1): TreeUtil

### Community 312 - "Community 312"
Cohesion: 0.29
Nodes (7): aichat-claw Plugin（openclaw 插件）, aichat-node（统一桥接层）, CORS 跨域配置, HuLa-Server（Java 微服务）, openclaw（外部 AI 引擎）, WebSocket query token 鉴权, 保留 aichat-node 中间层的理由

### Community 313 - "Community 313"
Cohesion: 0.29
Nodes (7): aichat-plugins 插件桥接层, openclaw 外部AI引擎, openclaw纳入核心交付链, aichat-plugins仓库, 废弃openclaw, 引入aichat-plugins, aichat-plugins Communication Plugin (Node.js), Aiclaw WebSocket Events (streamStart/streamDelta/streamEnd)

### Community 314 - "Community 314"
Cohesion: 0.6
Nodes (1): CommentUtils

### Community 315 - "Community 315"
Cohesion: 0.4
Nodes (1): OnlineService

### Community 316 - "Community 316"
Cohesion: 0.33
Nodes (1): UserFriendsKeyBuilder

### Community 317 - "Community 317"
Cohesion: 0.33
Nodes (1): ReverseFriendsKeyBuilder

### Community 318 - "Community 318"
Cohesion: 0.33
Nodes (1): DeviceNodeMapping

### Community 319 - "Community 319"
Cohesion: 0.33
Nodes (1): ACTrieNode

### Community 320 - "Community 320"
Cohesion: 0.33
Nodes (1): BatchCache

### Community 321 - "Community 321"
Cohesion: 0.33
Nodes (1): VideoMsgHandler

### Community 322 - "Community 322"
Cohesion: 0.33
Nodes (1): SoundMsgHandler

### Community 323 - "Community 323"
Cohesion: 0.33
Nodes (1): FileMsgHandler

### Community 324 - "Community 324"
Cohesion: 0.33
Nodes (1): ImgMsgHandler

### Community 325 - "Community 325"
Cohesion: 0.33
Nodes (1): MergeMsgHandler

### Community 326 - "Community 326"
Cohesion: 0.33
Nodes (1): MapMsgHandler

### Community 327 - "Community 327"
Cohesion: 0.33
Nodes (1): SystemMsgHandler

### Community 328 - "Community 328"
Cohesion: 0.33
Nodes (1): EmojisMsgHandler

### Community 329 - "Community 329"
Cohesion: 0.6
Nodes (1): RoomAnnouncementsCache

### Community 330 - "Community 330"
Cohesion: 0.33
Nodes (1): FeedCommentService

### Community 331 - "Community 331"
Cohesion: 0.33
Nodes (1): UserApplyDao

### Community 332 - "Community 332"
Cohesion: 0.33
Nodes (1): AiclawDao

### Community 333 - "Community 333"
Cohesion: 0.4
Nodes (3): Role, RoleDao, RoleMapper

### Community 334 - "Community 334"
Cohesion: 0.4
Nodes (1): WxPortalController

### Community 335 - "Community 335"
Cohesion: 0.33
Nodes (2): DataScopeProvider, TestDataScopeProviderImpl

### Community 336 - "Community 336"
Cohesion: 0.33
Nodes (1): DefAreaController

### Community 337 - "Community 337"
Cohesion: 0.33
Nodes (1): ExtendNoticeController

### Community 338 - "Community 338"
Cohesion: 0.4
Nodes (1): BaseEmployeeRoleRelManager

### Community 339 - "Community 339"
Cohesion: 0.4
Nodes (1): DefTenantManagerImpl

### Community 340 - "Community 340"
Cohesion: 0.6
Nodes (1): DefMsgTemplateServiceImpl

### Community 341 - "Community 341"
Cohesion: 0.33
Nodes (0): 

### Community 342 - "Community 342"
Cohesion: 0.33
Nodes (1): UserInfoService

### Community 343 - "Community 343"
Cohesion: 0.33
Nodes (1): UserInfoServiceImpl

### Community 344 - "Community 344"
Cohesion: 0.33
Nodes (0): 

### Community 345 - "Community 345"
Cohesion: 0.4
Nodes (1): GeneralController

### Community 346 - "Community 346"
Cohesion: 0.33
Nodes (1): ResourceController

### Community 347 - "Community 347"
Cohesion: 0.4
Nodes (1): MySwaggerXForwardedHeadersFilter

### Community 348 - "Community 348"
Cohesion: 0.33
Nodes (1): CorsConfiguration

### Community 349 - "Community 349"
Cohesion: 0.53
Nodes (1): MidjourneyApi

### Community 350 - "Community 350"
Cohesion: 0.33
Nodes (1): SiliconFlowVideoApi

### Community 351 - "Community 351"
Cohesion: 0.53
Nodes (1): LambdaUtils

### Community 352 - "Community 352"
Cohesion: 0.33
Nodes (1): BaseController

### Community 353 - "Community 353"
Cohesion: 0.33
Nodes (1): SuperCacheService

### Community 354 - "Community 354"
Cohesion: 0.33
Nodes (1): SuperCacheServiceImpl

### Community 355 - "Community 355"
Cohesion: 0.33
Nodes (1): PageParams

### Community 356 - "Community 356"
Cohesion: 0.33
Nodes (1): BytesWrapper

### Community 357 - "Community 357"
Cohesion: 0.4
Nodes (1): RedisDistributedLock

### Community 358 - "Community 358"
Cohesion: 0.4
Nodes (1): BaseLikeTypeHandler

### Community 359 - "Community 359"
Cohesion: 0.47
Nodes (1): BaseEnum

### Community 360 - "Community 360"
Cohesion: 0.47
Nodes (1): AntiSqlFilterUtils

### Community 361 - "Community 361"
Cohesion: 0.47
Nodes (1): DbPlusUtil

### Community 362 - "Community 362"
Cohesion: 0.53
Nodes (1): MyKnife4jOpenApiCustomizer

### Community 363 - "Community 363"
Cohesion: 0.47
Nodes (1): MQProducer

### Community 364 - "Community 364"
Cohesion: 0.4
Nodes (2): LoginMessageDTO, MsgLoginConsumer

### Community 365 - "Community 365"
Cohesion: 0.4
Nodes (2): ScanSuccessConsumer, ScanSuccessMessageDTO

### Community 366 - "Community 366"
Cohesion: 0.5
Nodes (1): TypeConverts

### Community 367 - "Community 367"
Cohesion: 0.4
Nodes (1): Branch

### Community 368 - "Community 368"
Cohesion: 0.4
Nodes (1): BaseLogAspect

### Community 369 - "Community 369"
Cohesion: 0.5
Nodes (2): get(), match()

### Community 370 - "Community 370"
Cohesion: 0.5
Nodes (2): get(), match()

### Community 371 - "Community 371"
Cohesion: 0.5
Nodes (2): get(), match()

### Community 372 - "Community 372"
Cohesion: 0.6
Nodes (3): get(), getCode(), match()

### Community 373 - "Community 373"
Cohesion: 0.6
Nodes (3): get(), getCode(), match()

### Community 374 - "Community 374"
Cohesion: 0.5
Nodes (1): CursorUtils

### Community 375 - "Community 375"
Cohesion: 0.4
Nodes (1): SensitiveWordFilter

### Community 376 - "Community 376"
Cohesion: 0.4
Nodes (1): CommonUrlDiscover

### Community 377 - "Community 377"
Cohesion: 0.4
Nodes (1): WxUrlDiscover

### Community 378 - "Community 378"
Cohesion: 0.5
Nodes (1): MessageSendListener

### Community 379 - "Community 379"
Cohesion: 0.7
Nodes (1): MsgSendConsumer

### Community 380 - "Community 380"
Cohesion: 0.5
Nodes (1): RoomCache

### Community 381 - "Community 381"
Cohesion: 0.6
Nodes (1): WxMsgService

### Community 382 - "Community 382"
Cohesion: 0.6
Nodes (1): ApplyServiceImpl

### Community 383 - "Community 383"
Cohesion: 0.5
Nodes (1): SlideWindow

### Community 384 - "Community 384"
Cohesion: 0.5
Nodes (1): ApplyController

### Community 385 - "Community 385"
Cohesion: 0.4
Nodes (2): BaseLoginLogApi, LoginRankDTO

### Community 386 - "Community 386"
Cohesion: 0.4
Nodes (1): BaseOperationLogController

### Community 387 - "Community 387"
Cohesion: 0.4
Nodes (1): DefDictController

### Community 388 - "Community 388"
Cohesion: 0.4
Nodes (1): DefDictItemController

### Community 389 - "Community 389"
Cohesion: 0.6
Nodes (1): DefInterfaceServiceImpl

### Community 390 - "Community 390"
Cohesion: 0.5
Nodes (1): DefParameterManagerImpl

### Community 391 - "Community 391"
Cohesion: 0.5
Nodes (1): BaseEmployeeOrgRelManager

### Community 392 - "Community 392"
Cohesion: 0.4
Nodes (1): BaseEmployeeManagerImpl

### Community 393 - "Community 393"
Cohesion: 0.4
Nodes (1): BaseEmployeeRoleRelManagerImpl

### Community 394 - "Community 394"
Cohesion: 0.4
Nodes (1): DefResourceApiManagerImpl

### Community 395 - "Community 395"
Cohesion: 0.4
Nodes (1): DefAreaService

### Community 396 - "Community 396"
Cohesion: 0.4
Nodes (1): DefDictService

### Community 397 - "Community 397"
Cohesion: 0.5
Nodes (1): DefDictItemServiceImpl

### Community 398 - "Community 398"
Cohesion: 0.6
Nodes (1): BasePositionServiceImpl

### Community 399 - "Community 399"
Cohesion: 0.4
Nodes (1): DefUserTenantRelService

### Community 400 - "Community 400"
Cohesion: 0.4
Nodes (1): BaseRoleMapper

### Community 401 - "Community 401"
Cohesion: 0.4
Nodes (1): DictService

### Community 402 - "Community 402"
Cohesion: 0.4
Nodes (1): CaptchaService

### Community 403 - "Community 403"
Cohesion: 0.4
Nodes (1): CaptchaController

### Community 404 - "Community 404"
Cohesion: 0.4
Nodes (1): EchoController

### Community 405 - "Community 405"
Cohesion: 0.6
Nodes (1): OpenApi3Controller

### Community 406 - "Community 406"
Cohesion: 0.4
Nodes (1): SiliconFlowImageApi

### Community 407 - "Community 407"
Cohesion: 0.7
Nodes (1): SuperCacheController

### Community 408 - "Community 408"
Cohesion: 0.4
Nodes (1): QueryController

### Community 409 - "Community 409"
Cohesion: 0.4
Nodes (1): CaffeineAutoConfigure

### Community 410 - "Community 410"
Cohesion: 0.4
Nodes (1): FastJsonJsonRedisSerializer

### Community 411 - "Community 411"
Cohesion: 0.4
Nodes (1): ProtoStuffSerializer

### Community 412 - "Community 412"
Cohesion: 0.7
Nodes (1): TenantLineAnnotationRegister

### Community 413 - "Community 413"
Cohesion: 0.4
Nodes (1): BizException

### Community 414 - "Community 414"
Cohesion: 0.4
Nodes (1): BaseUncheckedException

### Community 415 - "Community 415"
Cohesion: 0.4
Nodes (1): CommonException

### Community 416 - "Community 416"
Cohesion: 0.4
Nodes (1): BaseCheckedException

### Community 417 - "Community 417"
Cohesion: 0.4
Nodes (1): KillParam

### Community 418 - "Community 418"
Cohesion: 0.4
Nodes (1): IdleBeatParam

### Community 419 - "Community 419"
Cohesion: 0.4
Nodes (1): CacheLoadKeys

### Community 420 - "Community 420"
Cohesion: 0.6
Nodes (1): Base62Encoder

### Community 421 - "Community 421"
Cohesion: 0.4
Nodes (5): Web端改造后端待办, 联调接口契约, CORS白名单收敛, REQ-001 Web端改造, WS query token支持

### Community 422 - "Community 422"
Cohesion: 0.5
Nodes (1): CommentUtilsTest

### Community 423 - "Community 423"
Cohesion: 0.5
Nodes (1): TemplateUtils

### Community 424 - "Community 424"
Cohesion: 0.5
Nodes (1): BranchBuilder

### Community 425 - "Community 425"
Cohesion: 0.5
Nodes (1): DefGenTestTreeController

### Community 426 - "Community 426"
Cohesion: 0.83
Nodes (1): Base64Util

### Community 427 - "Community 427"
Cohesion: 0.5
Nodes (1): ToolsUtil

### Community 428 - "Community 428"
Cohesion: 0.5
Nodes (1): VerificationCodeCacheKeyBuilder

### Community 429 - "Community 429"
Cohesion: 0.67
Nodes (1): ContextArgumentResolver

### Community 430 - "Community 430"
Cohesion: 0.5
Nodes (1): DataScopeContext

### Community 431 - "Community 431"
Cohesion: 0.67
Nodes (1): AppendixSaveVO

### Community 432 - "Community 432"
Cohesion: 0.83
Nodes (1): UserBackpackServiceImpl

### Community 433 - "Community 433"
Cohesion: 0.5
Nodes (1): FixWindow

### Community 434 - "Community 434"
Cohesion: 0.5
Nodes (1): LeakyBucketRateLimiter

### Community 435 - "Community 435"
Cohesion: 0.67
Nodes (1): Randoms

### Community 436 - "Community 436"
Cohesion: 0.5
Nodes (1): DefParameterController

### Community 437 - "Community 437"
Cohesion: 0.5
Nodes (1): BasePositionController

### Community 438 - "Community 438"
Cohesion: 0.5
Nodes (1): BaseEmployeeTestController

### Community 439 - "Community 439"
Cohesion: 0.5
Nodes (1): ExtendNoticeService

### Community 440 - "Community 440"
Cohesion: 0.5
Nodes (1): ExtendNoticeServiceImpl

### Community 441 - "Community 441"
Cohesion: 0.5
Nodes (1): DefDictManagerImpl

### Community 442 - "Community 442"
Cohesion: 0.5
Nodes (1): BaseEmployeeManager

### Community 443 - "Community 443"
Cohesion: 0.5
Nodes (1): BaseEmployeeOrgRelManagerImpl

### Community 444 - "Community 444"
Cohesion: 0.5
Nodes (1): DefApplicationManager

### Community 445 - "Community 445"
Cohesion: 0.5
Nodes (1): DefResourceApiManager

### Community 446 - "Community 446"
Cohesion: 0.5
Nodes (1): DefTenantApplicationRelManager

### Community 447 - "Community 447"
Cohesion: 0.5
Nodes (1): BaseOperationLogServiceImpl

### Community 448 - "Community 448"
Cohesion: 0.5
Nodes (1): DefUserTenantRelServiceImpl

### Community 449 - "Community 449"
Cohesion: 0.5
Nodes (1): BaseRoleResourceRelMapper

### Community 450 - "Community 450"
Cohesion: 0.5
Nodes (1): DefUserMapper

### Community 451 - "Community 451"
Cohesion: 0.5
Nodes (1): GateController

### Community 452 - "Community 452"
Cohesion: 0.67
Nodes (1): WebSocketHeaderFilter

### Community 453 - "Community 453"
Cohesion: 0.5
Nodes (1): CommonResponseDecorator

### Community 454 - "Community 454"
Cohesion: 0.5
Nodes (1): StpInterfaceServiceImpl

### Community 455 - "Community 455"
Cohesion: 0.5
Nodes (1): CacheUtils

### Community 456 - "Community 456"
Cohesion: 0.67
Nodes (1): SaveController

### Community 457 - "Community 457"
Cohesion: 0.5
Nodes (1): SuperSimpleController

### Community 458 - "Community 458"
Cohesion: 0.5
Nodes (1): SuperManager

### Community 459 - "Community 459"
Cohesion: 0.67
Nodes (1): SuperManagerImpl

### Community 460 - "Community 460"
Cohesion: 0.5
Nodes (1): DownloadFileStream

### Community 461 - "Community 461"
Cohesion: 0.67
Nodes (1): LogUtil

### Community 462 - "Community 462"
Cohesion: 0.5
Nodes (1): DefValueHelper

### Community 463 - "Community 463"
Cohesion: 0.5
Nodes (1): BaseException

### Community 464 - "Community 464"
Cohesion: 0.5
Nodes (1): ForbiddenException

### Community 465 - "Community 465"
Cohesion: 0.67
Nodes (1): DefCacheLoader

### Community 466 - "Community 466"
Cohesion: 0.5
Nodes (1): InfoSlf4jFeignLogger

### Community 467 - "Community 467"
Cohesion: 0.5
Nodes (4): ADR-20260310 Include openclaw in Core Delivery Chain, ADR-20260313 Replace openclaw with aichat-plugins, openclaw Replaced: Need Multi-Claw Support, 石哥 (DevOps)

### Community 468 - "Community 468"
Cohesion: 0.67
Nodes (1): NettyShutdownConfig

### Community 469 - "Community 469"
Cohesion: 0.67
Nodes (1): SessionRecoveryService

### Community 470 - "Community 470"
Cohesion: 0.67
Nodes (1): NodeIdEnvironmentProcessor

### Community 471 - "Community 471"
Cohesion: 0.67
Nodes (1): NacosConfig

### Community 472 - "Community 472"
Cohesion: 0.67
Nodes (1): WsServerApplication

### Community 473 - "Community 473"
Cohesion: 0.67
Nodes (1): ParameterKey

### Community 474 - "Community 474"
Cohesion: 1.0
Nodes (1): UserResolverBootServiceImpl

### Community 475 - "Community 475"
Cohesion: 1.0
Nodes (1): UserResolverServiceImpl

### Community 476 - "Community 476"
Cohesion: 0.67
Nodes (0): 

### Community 477 - "Community 477"
Cohesion: 0.67
Nodes (1): OffLineResp

### Community 478 - "Community 478"
Cohesion: 0.67
Nodes (1): CacheConfig

### Community 479 - "Community 479"
Cohesion: 0.67
Nodes (1): AbstractBuilder

### Community 480 - "Community 480"
Cohesion: 0.67
Nodes (1): ImageBuilder

### Community 481 - "Community 481"
Cohesion: 0.67
Nodes (1): IpResult

### Community 482 - "Community 482"
Cohesion: 0.67
Nodes (2): infoReq, SummeryInfoReq

### Community 483 - "Community 483"
Cohesion: 1.0
Nodes (2): get(), match()

### Community 484 - "Community 484"
Cohesion: 1.0
Nodes (1): DefResourceFacadeImpl

### Community 485 - "Community 485"
Cohesion: 1.0
Nodes (2): get(), match()

### Community 486 - "Community 486"
Cohesion: 1.0
Nodes (2): get(), match()

### Community 487 - "Community 487"
Cohesion: 0.67
Nodes (1): ContextPathFilter

### Community 488 - "Community 488"
Cohesion: 0.67
Nodes (1): WebFluxExceptionConfig

### Community 489 - "Community 489"
Cohesion: 0.67
Nodes (1): MonitorServerApplication

### Community 490 - "Community 490"
Cohesion: 0.67
Nodes (1): XxlJobExecutorExampleBootApplicationTests

### Community 491 - "Community 491"
Cohesion: 0.67
Nodes (1): WebSocketConfig

### Community 492 - "Community 492"
Cohesion: 0.67
Nodes (1): ArrayValuable

### Community 493 - "Community 493"
Cohesion: 1.0
Nodes (1): UpdateController

### Community 494 - "Community 494"
Cohesion: 0.67
Nodes (1): FdfsConnectionPool

### Community 495 - "Community 495"
Cohesion: 0.67
Nodes (1): DownloadByteArray

### Community 496 - "Community 496"
Cohesion: 0.67
Nodes (1): FdfsColumnMapException

### Community 497 - "Community 497"
Cohesion: 0.67
Nodes (1): DmWallProviderCreator

### Community 498 - "Community 498"
Cohesion: 0.67
Nodes (1): TenantLineHelper

### Community 499 - "Community 499"
Cohesion: 0.67
Nodes (1): CheckedFunction

### Community 500 - "Community 500"
Cohesion: 1.0
Nodes (1): ClassUtils

### Community 501 - "Community 501"
Cohesion: 1.0
Nodes (1): BeanPlusUtil

### Community 502 - "Community 502"
Cohesion: 0.67
Nodes (1): LocalDateTimeToTimestampSerializer

### Community 503 - "Community 503"
Cohesion: 1.0
Nodes (1): TreeEntity

### Community 504 - "Community 504"
Cohesion: 0.67
Nodes (1): Swagger2Configuration

### Community 505 - "Community 505"
Cohesion: 0.67
Nodes (1): SwaggerWebMvcConfigurer

### Community 506 - "Community 506"
Cohesion: 0.67
Nodes (1): BufferedUidProvider

### Community 507 - "Community 507"
Cohesion: 0.67
Nodes (1): StringEvent

### Community 508 - "Community 508"
Cohesion: 0.67
Nodes (3): luohuo-ws-server WebSocket实时通讯（端口18762）, WebSocket Push Architecture Diagram, WebSocket流式消息事件

### Community 509 - "Community 509"
Cohesion: 0.67
Nodes (3): 对外人设（public_persona）, 安全 prompt 注入机制, sessionKey 会话上下文隔离

### Community 510 - "Community 510"
Cohesion: 0.67
Nodes (3): AI助理 aiclaw, WS流式三阶段协议, 复用现有WS通道 (ws://host/api/ws/ws)

### Community 511 - "Community 511"
Cohesion: 0.67
Nodes (3): Chat Completions API, 私有网络部署要求, SSE流式响应

### Community 512 - "Community 512"
Cohesion: 0.67
Nodes (3): 12-Step Message Execution Flow, Message Execution Flow Architecture Diagram, RocketMQ Topic Topology (8 topics for msg flow)

### Community 513 - "Community 513"
Cohesion: 0.67
Nodes (3): Dual-Mode Architecture: Monolith + Microservices, luohuo-base-facade Module (Base API Facade), luohuo-im-facade Module (IM API Facade)

### Community 514 - "Community 514"
Cohesion: 0.67
Nodes (3): 5-Layer Call Model: controller -> biz -> service -> manager -> mapper, Jenkins Maven Install Util Module Build Output, luohuo-util Build Instructions

### Community 515 - "Community 515"
Cohesion: 1.0
Nodes (1): WebFluxConfig

### Community 516 - "Community 516"
Cohesion: 1.0
Nodes (1): StartSignalingVO

### Community 517 - "Community 517"
Cohesion: 1.0
Nodes (1): DomesticNewsListVo

### Community 518 - "Community 518"
Cohesion: 1.0
Nodes (1): DomesticNewsVo

### Community 519 - "Community 519"
Cohesion: 1.0
Nodes (1): BizMqQueue

### Community 520 - "Community 520"
Cohesion: 1.0
Nodes (1): SwaggerConstants

### Community 521 - "Community 521"
Cohesion: 1.0
Nodes (1): JobConstant

### Community 522 - "Community 522"
Cohesion: 1.0
Nodes (0): 

### Community 523 - "Community 523"
Cohesion: 1.0
Nodes (1): WSLoginSuccess

### Community 524 - "Community 524"
Cohesion: 1.0
Nodes (1): ConverseMessageDto

### Community 525 - "Community 525"
Cohesion: 1.0
Nodes (1): WSMessageRead

### Community 526 - "Community 526"
Cohesion: 1.0
Nodes (1): WSLoginUrl

### Community 527 - "Community 527"
Cohesion: 1.0
Nodes (1): WSMessage

### Community 528 - "Community 528"
Cohesion: 1.0
Nodes (1): WSMsgRecall

### Community 529 - "Community 529"
Cohesion: 1.0
Nodes (1): WSAiclawAuthRequest

### Community 530 - "Community 530"
Cohesion: 1.0
Nodes (1): StreamEndPersistDTO

### Community 531 - "Community 531"
Cohesion: 1.0
Nodes (0): 

### Community 532 - "Community 532"
Cohesion: 1.0
Nodes (1): DebounceInfo

### Community 533 - "Community 533"
Cohesion: 1.0
Nodes (1): GroupConst

### Community 534 - "Community 534"
Cohesion: 1.0
Nodes (1): AbstractHandler

### Community 535 - "Community 535"
Cohesion: 1.0
Nodes (1): RequestInfo

### Community 536 - "Community 536"
Cohesion: 1.0
Nodes (1): WSChannelExtraDTO

### Community 537 - "Community 537"
Cohesion: 1.0
Nodes (1): BaseFileDTO

### Community 538 - "Community 538"
Cohesion: 1.0
Nodes (1): UploadUrlReq

### Community 539 - "Community 539"
Cohesion: 1.0
Nodes (1): MsgReq

### Community 540 - "Community 540"
Cohesion: 1.0
Nodes (1): ChatMessageReadInfoReq

### Community 541 - "Community 541"
Cohesion: 1.0
Nodes (1): ChatMessageBaseReq

### Community 542 - "Community 542"
Cohesion: 1.0
Nodes (0): 

### Community 543 - "Community 543"
Cohesion: 1.0
Nodes (0): 

### Community 544 - "Community 544"
Cohesion: 1.0
Nodes (1): ExtendMsgRecipientPageQuery

### Community 545 - "Community 545"
Cohesion: 1.0
Nodes (1): FileChunkCheckDTO

### Community 546 - "Community 546"
Cohesion: 1.0
Nodes (1): FileUploadDTO

### Community 547 - "Community 547"
Cohesion: 1.0
Nodes (1): FileChunksMergeDTO

### Community 548 - "Community 548"
Cohesion: 1.0
Nodes (1): FileGetUrlBO

### Community 549 - "Community 549"
Cohesion: 1.0
Nodes (1): DefUserApplicationSaveVO

### Community 550 - "Community 550"
Cohesion: 1.0
Nodes (1): RouterMetaConfig

### Community 551 - "Community 551"
Cohesion: 1.0
Nodes (1): DefUserApplicationPageQuery

### Community 552 - "Community 552"
Cohesion: 1.0
Nodes (1): DefResourceApiPageQuery

### Community 553 - "Community 553"
Cohesion: 1.0
Nodes (1): BaseRoleResourceRelPageQuery

### Community 554 - "Community 554"
Cohesion: 1.0
Nodes (1): BaseOrgRoleRelPageQuery

### Community 555 - "Community 555"
Cohesion: 1.0
Nodes (1): BaseEmployeeRoleRelPageQuery

### Community 556 - "Community 556"
Cohesion: 1.0
Nodes (1): BaseEmployeeOrgRelPageQuery

### Community 557 - "Community 557"
Cohesion: 1.0
Nodes (0): 

### Community 558 - "Community 558"
Cohesion: 1.0
Nodes (1): RegisterVO

### Community 559 - "Community 559"
Cohesion: 1.0
Nodes (1): OrderedConstant

### Community 560 - "Community 560"
Cohesion: 1.0
Nodes (1): SecurityProperties

### Community 561 - "Community 561"
Cohesion: 1.0
Nodes (0): 

### Community 562 - "Community 562"
Cohesion: 1.0
Nodes (1): FdfsClientConfig

### Community 563 - "Community 563"
Cohesion: 1.0
Nodes (1): StatusConstants

### Community 564 - "Community 564"
Cohesion: 1.0
Nodes (1): LuohuoUtil

### Community 565 - "Community 565"
Cohesion: 1.0
Nodes (1): ListDTO

### Community 566 - "Community 566"
Cohesion: 1.0
Nodes (2): StreamProcessor（流式中继+落库）, WS 流式协议（STREAM_START/DELTA/END）

### Community 567 - "Community 567"
Cohesion: 1.0
Nodes (2): AES-256-GCM 加密, 双 token 模型（激活 token + 连接 token）

### Community 568 - "Community 568"
Cohesion: 1.0
Nodes (2): ClawAdapter 统一接口, WS RPC 替代 HTTP SSE 的理由

### Community 569 - "Community 569"
Cohesion: 1.0
Nodes (2): 分支策略 main/develop/feature/*, Docker化开发-测试-发布流程

### Community 570 - "Community 570"
Cohesion: 1.0
Nodes (2): 锋哥 (Backend Owner), REQ-002 Backend Tasks (B1-B15)

### Community 571 - "Community 571"
Cohesion: 1.0
Nodes (2): REQ-003 Non-Owner Communication with aiclaw, REQ-003 Non-Owner aiclaw Communication Spec

### Community 572 - "Community 572"
Cohesion: 1.0
Nodes (2): 阿飞 (Frontend Owner), REQ-002 Frontend Tasks (F1-F12)

### Community 573 - "Community 573"
Cohesion: 1.0
Nodes (2): @Echo Annotation Constant Generation Caveats, luohuo-model: Business Entities, Enums, VOs

### Community 574 - "Community 574"
Cohesion: 1.0
Nodes (2): luohuo-cache-starter: Redis/Caffeine Cache Abstraction, Redis Cache & Session Store

### Community 575 - "Community 575"
Cohesion: 1.0
Nodes (0): 

### Community 576 - "Community 576"
Cohesion: 1.0
Nodes (0): 

### Community 577 - "Community 577"
Cohesion: 1.0
Nodes (0): 

### Community 578 - "Community 578"
Cohesion: 1.0
Nodes (0): 

### Community 579 - "Community 579"
Cohesion: 1.0
Nodes (0): 

### Community 580 - "Community 580"
Cohesion: 1.0
Nodes (0): 

### Community 581 - "Community 581"
Cohesion: 1.0
Nodes (0): 

### Community 582 - "Community 582"
Cohesion: 1.0
Nodes (0): 

### Community 583 - "Community 583"
Cohesion: 1.0
Nodes (0): 

### Community 584 - "Community 584"
Cohesion: 1.0
Nodes (0): 

### Community 585 - "Community 585"
Cohesion: 1.0
Nodes (0): 

### Community 586 - "Community 586"
Cohesion: 1.0
Nodes (1): luohuo-oauth-server 认证服务（端口18761）

### Community 587 - "Community 587"
Cohesion: 1.0
Nodes (1): luohuo-im-server IM业务服务（端口18763）

### Community 588 - "Community 588"
Cohesion: 1.0
Nodes (1): 外部中间件集群（MySQL/Redis/RocketMQ @10.38.10.10）

### Community 589 - "Community 589"
Cohesion: 1.0
Nodes (1): 环境配置分层方案（.env.common/.env.dev/.env.staging/.env.prod）

### Community 590 - "Community 590"
Cohesion: 1.0
Nodes (1): 后端构建环境（个人开发目录+容器分离）

### Community 591 - "Community 591"
Cohesion: 1.0
Nodes (1): 前端构建环境（多Builder容器+产物目录规划）

### Community 592 - "Community 592"
Cohesion: 1.0
Nodes (1): 团队角色与职责（6职能划分）

### Community 593 - "Community 593"
Cohesion: 1.0
Nodes (1): manager 项目经理

### Community 594 - "Community 594"
Cohesion: 1.0
Nodes (1): server-dev 后端开发（锋哥，HuLa-Server微服务）

### Community 595 - "Community 595"
Cohesion: 1.0
Nodes (1): frontend-dev 前端开发（阿飞，HuLa+HuLa-Admin）

### Community 596 - "Community 596"
Cohesion: 1.0
Nodes (1): plugin-dev 插件开发（aichat-plugins通讯桥接）

### Community 597 - "Community 597"
Cohesion: 1.0
Nodes (1): backend-tester 后端测试

### Community 598 - "Community 598"
Cohesion: 1.0
Nodes (1): ui-tester 前端UI测试

### Community 599 - "Community 599"
Cohesion: 1.0
Nodes (1): reviewer 评审人员

### Community 600 - "Community 600"
Cohesion: 1.0
Nodes (1): 团队协作流程

### Community 601 - "Community 601"
Cohesion: 1.0
Nodes (1): RocketMQ Topic清单（8个Topic）

### Community 602 - "Community 602"
Cohesion: 1.0
Nodes (1): REQ-001: Web 端改造

### Community 603 - "Community 603"
Cohesion: 1.0
Nodes (1): REQ-002: AIclaw AI 助理（首期）

### Community 604 - "Community 604"
Cohesion: 1.0
Nodes (1): REQ-003: 非 owner 与 aiclaw 通讯

### Community 605 - "Community 605"
Cohesion: 1.0
Nodes (1): REQ-001 联调接口契约

### Community 606 - "Community 606"
Cohesion: 1.0
Nodes (1): IM 服务前端接口规范

### Community 607 - "Community 607"
Cohesion: 1.0
Nodes (1): REQ-001 Web端改造后端协作清单

### Community 608 - "Community 608"
Cohesion: 1.0
Nodes (1): 环境搭建任务分派

### Community 609 - "Community 609"
Cohesion: 1.0
Nodes (1): REQ-002 后端任务

### Community 610 - "Community 610"
Cohesion: 1.0
Nodes (1): REQ-002 前端任务

### Community 611 - "Community 611"
Cohesion: 1.0
Nodes (1): REQ-003 前端任务

### Community 612 - "Community 612"
Cohesion: 1.0
Nodes (1): 看板（Kanban）

### Community 613 - "Community 613"
Cohesion: 1.0
Nodes (1): REQ-003 后端任务

### Community 614 - "Community 614"
Cohesion: 1.0
Nodes (1): REQ-002 前端联调测试案例

### Community 615 - "Community 615"
Cohesion: 1.0
Nodes (1): REQ-002 需求调整：激活流程简化

### Community 616 - "Community 616"
Cohesion: 1.0
Nodes (1): 关系说明（relation_desc）

### Community 617 - "Community 617"
Cohesion: 1.0
Nodes (1): Agent Tools（HuLa 能力注入）

### Community 618 - "Community 618"
Cohesion: 1.0
Nodes (1): 消息防抖+流式堆积机制

### Community 619 - "Community 619"
Cohesion: 1.0
Nodes (1): R<T> 统一响应体格式

### Community 620 - "Community 620"
Cohesion: 1.0
Nodes (1): 游标翻页（cursor pagination）

### Community 621 - "Community 621"
Cohesion: 1.0
Nodes (1): userType 枚举值 (1-4)

### Community 622 - "Community 622"
Cohesion: 1.0
Nodes (1): 机器码（UUID 设备标识）

### Community 623 - "Community 623"
Cohesion: 1.0
Nodes (1): Docker 开发环境（aichat-dev-net）

### Community 624 - "Community 624"
Cohesion: 1.0
Nodes (1): Nacos 配置管理

### Community 625 - "Community 625"
Cohesion: 1.0
Nodes (1): aiclaw 详情显示主人信息

### Community 626 - "Community 626"
Cohesion: 1.0
Nodes (1): aiclaw 管理详情页

### Community 627 - "Community 627"
Cohesion: 1.0
Nodes (1): aiclaw 对话记录只读查看

### Community 628 - "Community 628"
Cohesion: 1.0
Nodes (1): 不复用 im_user_friend.remark 的理由

### Community 629 - "Community 629"
Cohesion: 1.0
Nodes (1): 取消 B24 sent_at 的理由

### Community 630 - "Community 630"
Cohesion: 1.0
Nodes (1): 复用现有消息查询 Service 的理由

### Community 631 - "Community 631"
Cohesion: 1.0
Nodes (1): 引入双 token 模型的理由

### Community 632 - "Community 632"
Cohesion: 1.0
Nodes (1): im_aiclaw 扩展表

### Community 633 - "Community 633"
Cohesion: 1.0
Nodes (1): 双Token模型

### Community 634 - "Community 634"
Cohesion: 1.0
Nodes (1): aichat activate CLI一键激活

### Community 635 - "Community 635"
Cohesion: 1.0
Nodes (1): 统一防抖机制 (2s缓冲/5条上限/10s最大等待)

### Community 636 - "Community 636"
Cohesion: 1.0
Nodes (1): 适配插件交付边界

### Community 637 - "Community 637"
Cohesion: 1.0
Nodes (1): 发布清单

### Community 638 - "Community 638"
Cohesion: 1.0
Nodes (1): OpenClaw External API Survey

### Community 639 - "Community 639"
Cohesion: 1.0
Nodes (1): REQ-003 Frontend Tasks (F14-F19)

### Community 640 - "Community 640"
Cohesion: 1.0
Nodes (1): RocketMQ 5.3.2 Message Middleware

### Community 641 - "Community 641"
Cohesion: 1.0
Nodes (1): Nacos v2.3.2 Service Registry & Config Center

### Community 642 - "Community 642"
Cohesion: 1.0
Nodes (1): MySQL 8.0.30 Persistent Database (hula)

### Community 643 - "Community 643"
Cohesion: 1.0
Nodes (1): Three-Layer Branch Strategy (master/dev/feature)

### Community 644 - "Community 644"
Cohesion: 1.0
Nodes (1): Runtime Integration Testing Environment

### Community 645 - "Community 645"
Cohesion: 1.0
Nodes (1): luohuo-validator-starter: Hibernate Validator Wrapper

### Community 646 - "Community 646"
Cohesion: 1.0
Nodes (1): luohuo-uid: Distributed UID Generator (Baidu uid-generator fork)

## Ambiguous Edges - Review These
- `HuLa-Server Instant Messaging System` → `Email SMTP Configuration for Registration`  [AMBIGUOUS]
  docs/install/服务端部署文档.md · relation: shows

## Knowledge Gaps
- **945 isolated node(s):** `ServletWebSocketHandlerAdapter`, `StreamContext`, `CloseRoomReq`, `KickUserReq`, `MuteAllReq` (+940 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 515`** (2 nodes): `WebFluxConfig.java`, `WebFluxConfig`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 516`** (2 nodes): `StartSignalingVO.java`, `StartSignalingVO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 517`** (2 nodes): `DomesticNewsListVo.java`, `DomesticNewsListVo`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 518`** (2 nodes): `DomesticNewsVo.java`, `DomesticNewsVo`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 519`** (2 nodes): `BizMqQueue.java`, `BizMqQueue`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 520`** (2 nodes): `SwaggerConstants.java`, `SwaggerConstants`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 521`** (2 nodes): `JobConstant.java`, `JobConstant`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 522`** (2 nodes): `BooleanEnum.java`, `eq()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 523`** (2 nodes): `WSLoginSuccess.java`, `WSLoginSuccess`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 524`** (2 nodes): `ConverseMessageDto.java`, `ConverseMessageDto`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 525`** (2 nodes): `WSMessageRead.java`, `WSMessageRead`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 526`** (2 nodes): `WSLoginUrl.java`, `WSLoginUrl`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 527`** (2 nodes): `WSMessage.java`, `WSMessage`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 528`** (2 nodes): `WSMsgRecall.java`, `WSMsgRecall`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 529`** (2 nodes): `WSAiclawAuthRequest.java`, `WSAiclawAuthRequest`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 530`** (2 nodes): `StreamEndPersistDTO.java`, `StreamEndPersistDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 531`** (2 nodes): `WSPushTypeEnum.java`, `of()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 532`** (2 nodes): `DebounceInfo.java`, `DebounceInfo`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 533`** (2 nodes): `GroupConst.java`, `GroupConst`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 534`** (2 nodes): `AbstractHandler.java`, `AbstractHandler`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 535`** (2 nodes): `RequestInfo.java`, `RequestInfo`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 536`** (2 nodes): `WSChannelExtraDTO.java`, `WSChannelExtraDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 537`** (2 nodes): `BaseFileDTO.java`, `BaseFileDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 538`** (2 nodes): `UploadUrlReq.java`, `UploadUrlReq`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 539`** (2 nodes): `MsgReq.java`, `MsgReq`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 540`** (2 nodes): `ChatMessageReadInfoReq.java`, `ChatMessageReadInfoReq`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 541`** (2 nodes): `ChatMessageBaseReq.java`, `ChatMessageBaseReq`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 542`** (2 nodes): `OssSceneEnum.java`, `of()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 543`** (2 nodes): `GroupRoleAPPEnum.java`, `of()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 544`** (2 nodes): `ExtendMsgRecipientPageQuery.java`, `ExtendMsgRecipientPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 545`** (2 nodes): `FileChunkCheckDTO.java`, `FileChunkCheckDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 546`** (2 nodes): `FileUploadDTO.java`, `FileUploadDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 547`** (2 nodes): `FileChunksMergeDTO.java`, `FileChunksMergeDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 548`** (2 nodes): `FileGetUrlBO.java`, `FileGetUrlBO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 549`** (2 nodes): `DefUserApplicationSaveVO.java`, `DefUserApplicationSaveVO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 550`** (2 nodes): `RouterMetaConfig.java`, `RouterMetaConfig`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 551`** (2 nodes): `DefUserApplicationPageQuery.java`, `DefUserApplicationPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 552`** (2 nodes): `DefResourceApiPageQuery.java`, `DefResourceApiPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 553`** (2 nodes): `BaseRoleResourceRelPageQuery.java`, `BaseRoleResourceRelPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 554`** (2 nodes): `BaseOrgRoleRelPageQuery.java`, `BaseOrgRoleRelPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 555`** (2 nodes): `BaseEmployeeRoleRelPageQuery.java`, `BaseEmployeeRoleRelPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 556`** (2 nodes): `BaseEmployeeOrgRelPageQuery.java`, `BaseEmployeeOrgRelPageQuery`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 557`** (2 nodes): `ApplicationGrantTypeEnum.java`, `ApplicationGrantTypeEnum()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 558`** (2 nodes): `RegisterVO.java`, `RegisterVO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 559`** (2 nodes): `OrderedConstant.java`, `OrderedConstant`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 560`** (2 nodes): `SecurityProperties.java`, `SecurityProperties`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 561`** (2 nodes): `CacheType.java`, `eq()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 562`** (2 nodes): `FdfsClientConfig.java`, `FdfsClientConfig`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 563`** (2 nodes): `StatusConstants.java`, `StatusConstants`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 564`** (2 nodes): `LuohuoUtil.java`, `LuohuoUtil`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 565`** (2 nodes): `ListDTO.java`, `ListDTO`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 566`** (2 nodes): `StreamProcessor（流式中继+落库）`, `WS 流式协议（STREAM_START/DELTA/END）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 567`** (2 nodes): `AES-256-GCM 加密`, `双 token 模型（激活 token + 连接 token）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 568`** (2 nodes): `ClawAdapter 统一接口`, `WS RPC 替代 HTTP SSE 的理由`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 569`** (2 nodes): `分支策略 main/develop/feature/*`, `Docker化开发-测试-发布流程`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 570`** (2 nodes): `锋哥 (Backend Owner)`, `REQ-002 Backend Tasks (B1-B15)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 571`** (2 nodes): `REQ-003 Non-Owner Communication with aiclaw`, `REQ-003 Non-Owner aiclaw Communication Spec`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 572`** (2 nodes): `阿飞 (Frontend Owner)`, `REQ-002 Frontend Tasks (F1-F12)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 573`** (2 nodes): `@Echo Annotation Constant Generation Caveats`, `luohuo-model: Business Entities, Enums, VOs`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 574`** (2 nodes): `luohuo-cache-starter: Redis/Caffeine Cache Abstraction`, `Redis Cache & Session Store`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 575`** (1 nodes): `LoginProcessor.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 576`** (1 nodes): `WXMsgServiceImpl.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 577`** (1 nodes): `WsExceptionConfiguration.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 578`** (1 nodes): `GeberatorUIServer.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 579`** (1 nodes): `MultipartSizeConfiguration.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 580`** (1 nodes): `SecurityConfig.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 581`** (1 nodes): `package-info.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 582`** (1 nodes): `limit.lua`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 583`** (1 nodes): `OptLogType.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 584`** (1 nodes): `JobHandler.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 585`** (1 nodes): `ShardingUtil.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 586`** (1 nodes): `luohuo-oauth-server 认证服务（端口18761）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 587`** (1 nodes): `luohuo-im-server IM业务服务（端口18763）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 588`** (1 nodes): `外部中间件集群（MySQL/Redis/RocketMQ @10.38.10.10）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 589`** (1 nodes): `环境配置分层方案（.env.common/.env.dev/.env.staging/.env.prod）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 590`** (1 nodes): `后端构建环境（个人开发目录+容器分离）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 591`** (1 nodes): `前端构建环境（多Builder容器+产物目录规划）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 592`** (1 nodes): `团队角色与职责（6职能划分）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 593`** (1 nodes): `manager 项目经理`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 594`** (1 nodes): `server-dev 后端开发（锋哥，HuLa-Server微服务）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 595`** (1 nodes): `frontend-dev 前端开发（阿飞，HuLa+HuLa-Admin）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 596`** (1 nodes): `plugin-dev 插件开发（aichat-plugins通讯桥接）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 597`** (1 nodes): `backend-tester 后端测试`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 598`** (1 nodes): `ui-tester 前端UI测试`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 599`** (1 nodes): `reviewer 评审人员`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 600`** (1 nodes): `团队协作流程`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 601`** (1 nodes): `RocketMQ Topic清单（8个Topic）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 602`** (1 nodes): `REQ-001: Web 端改造`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 603`** (1 nodes): `REQ-002: AIclaw AI 助理（首期）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 604`** (1 nodes): `REQ-003: 非 owner 与 aiclaw 通讯`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 605`** (1 nodes): `REQ-001 联调接口契约`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 606`** (1 nodes): `IM 服务前端接口规范`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 607`** (1 nodes): `REQ-001 Web端改造后端协作清单`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 608`** (1 nodes): `环境搭建任务分派`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 609`** (1 nodes): `REQ-002 后端任务`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 610`** (1 nodes): `REQ-002 前端任务`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 611`** (1 nodes): `REQ-003 前端任务`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 612`** (1 nodes): `看板（Kanban）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 613`** (1 nodes): `REQ-003 后端任务`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 614`** (1 nodes): `REQ-002 前端联调测试案例`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 615`** (1 nodes): `REQ-002 需求调整：激活流程简化`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 616`** (1 nodes): `关系说明（relation_desc）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 617`** (1 nodes): `Agent Tools（HuLa 能力注入）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 618`** (1 nodes): `消息防抖+流式堆积机制`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 619`** (1 nodes): `R<T> 统一响应体格式`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 620`** (1 nodes): `游标翻页（cursor pagination）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 621`** (1 nodes): `userType 枚举值 (1-4)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 622`** (1 nodes): `机器码（UUID 设备标识）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 623`** (1 nodes): `Docker 开发环境（aichat-dev-net）`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 624`** (1 nodes): `Nacos 配置管理`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 625`** (1 nodes): `aiclaw 详情显示主人信息`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 626`** (1 nodes): `aiclaw 管理详情页`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 627`** (1 nodes): `aiclaw 对话记录只读查看`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 628`** (1 nodes): `不复用 im_user_friend.remark 的理由`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 629`** (1 nodes): `取消 B24 sent_at 的理由`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 630`** (1 nodes): `复用现有消息查询 Service 的理由`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 631`** (1 nodes): `引入双 token 模型的理由`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 632`** (1 nodes): `im_aiclaw 扩展表`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 633`** (1 nodes): `双Token模型`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 634`** (1 nodes): `aichat activate CLI一键激活`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 635`** (1 nodes): `统一防抖机制 (2s缓冲/5条上限/10s最大等待)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 636`** (1 nodes): `适配插件交付边界`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 637`** (1 nodes): `发布清单`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 638`** (1 nodes): `OpenClaw External API Survey`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 639`** (1 nodes): `REQ-003 Frontend Tasks (F14-F19)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 640`** (1 nodes): `RocketMQ 5.3.2 Message Middleware`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 641`** (1 nodes): `Nacos v2.3.2 Service Registry & Config Center`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 642`** (1 nodes): `MySQL 8.0.30 Persistent Database (hula)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 643`** (1 nodes): `Three-Layer Branch Strategy (master/dev/feature)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 644`** (1 nodes): `Runtime Integration Testing Environment`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 645`** (1 nodes): `luohuo-validator-starter: Hibernate Validator Wrapper`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 646`** (1 nodes): `luohuo-uid: Distributed UID Generator (Baidu uid-generator fork)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `HuLa-Server Instant Messaging System` and `Email SMTP Configuration for Registration`?**
  _Edge tagged AMBIGUOUS (relation: shows) - confidence is low._
- **Why does `StorageState` connect `Storage & Connection Pool` to `FastDFS File Storage`?**
  _High betweenness centrality (0.020) - this node is a cross-community bridge._
- **Why does `BaseRedis` connect `Redis Operations` to `Core Utils & Base Classes`?**
  _High betweenness centrality (0.017) - this node is a cross-community bridge._
- **Why does `TimeUtils` connect `Community 21` to `IM Business Services`?**
  _High betweenness centrality (0.011) - this node is a cross-community bridge._
- **What connects `ServletWebSocketHandlerAdapter`, `StreamContext`, `CloseRoomReq` to the rest of the system?**
  _945 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Core Utils & Base Classes` be split into smaller, more focused modules?**
  _Cohesion score 0.0 - nodes in this community are weakly interconnected._
- **Should `IM Business Services` be split into smaller, more focused modules?**
  _Cohesion score 0.0 - nodes in this community are weakly interconnected._