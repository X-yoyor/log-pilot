package com.logpilot.service;

import com.logpilot.domain.KnowledgeRule;
import com.logpilot.repo.KnowledgeRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 异常知识库：内置常见 Java 异常的根因与修复方案，支持页面自定义扩展。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeRuleRepository repo;

    private record CompiledRule(KnowledgeRule rule, Pattern pattern) {
    }

    private final List<CompiledRule> compiled = new CopyOnWriteArrayList<>();

    public record KnowledgeHit(Long ruleId, String name, String suggestion, String severity) {
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        List<KnowledgeRule> rules = repo.findByEnabledTrueOrderBySortOrderAscIdAsc();
        compiled.clear();
        for (KnowledgeRule r : rules) {
            try {
                compiled.add(new CompiledRule(r, Pattern.compile(r.getPattern())));
            } catch (Exception e) {
                log.warn("知识库规则正则无效, 已跳过: {} -> {}", r.getName(), r.getPattern());
            }
        }
        log.info("知识库已加载 {} 条启用规则", compiled.size());
    }

    /** 匹配顺序：异常类名 > 消息模板 > 顶层堆栈。 */
    public KnowledgeHit match(String exceptionClass, String template, String topFrame,
                              String stackTrace, String message) {
        String text = join(exceptionClass, "\n", template, "\n", topFrame, "\n",
                limit(stackTrace, 1500), "\n", limit(message, 800));
        for (CompiledRule cr : compiled) {
            if (cr.pattern().matcher(text).find()) {
                KnowledgeRule r = cr.rule();
                try {
                    r.setHitCount((r.getHitCount() == null ? 0 : r.getHitCount()) + 1);
                    repo.save(r);
                } catch (Exception ignore) {
                    // 计数失败不影响主流程
                }
                return new KnowledgeHit(r.getId(), r.getName(), r.getSuggestion(), r.getSeverity());
            }
        }
        return null;
    }

    @Transactional
    public void seedIfEmpty() {
        if (repo.count() > 0) return;
        List<KnowledgeRule> rules = BuiltInRules.build();
        repo.saveAll(rules);
        log.info("已内置 {} 条异常知识规则", rules.size());
        reload();
    }

    private static String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) sb.append(p);
        return sb.toString();
    }

    private static String limit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 内置规则集（正则匹配 异常类+模板+堆栈 组合文本）。 */
    static final class BuiltInRules {

        static KnowledgeRule r(String name, String pattern, String severity, String cause, String suggestion) {
            KnowledgeRule k = new KnowledgeRule();
            k.setName(name);
            k.setPattern(pattern);
            k.setSeverity(severity);
            k.setCause(cause);
            k.setSuggestion(suggestion);
            k.setBuiltin(true);
            k.setEnabled(true);
            return k;
        }

        static List<KnowledgeRule> build() {
            List<KnowledgeRule> all = new java.util.ArrayList<>(List.of(
                    r("空指针异常 NullPointerException", "NullPointerException",
                            "HIGH",
                            "对 null 对象调用方法、取属性或自动拆箱。常见于：数据库/缓存查询结果未判空直接使用、RPC 返回值未校验、Map.get() 返回 null、方法参数为 null。",
                            "1. 按堆栈中第一个业务包名的帧定位代码行，确认哪个对象为 null；\n" +
                                    "2. 对外部输入（DB 查询、RPC、Map.get、Optional.get）增加判空：`if (obj == null) return/throw 业务异常;`；\n" +
                                    "3. 集合/Optional 场景使用 `Optional.ofNullable(x).map(...).orElse(默认值)`；\n" +
                                    "4. 配置 IDE/静态扫描（SonarQube、SpotBugs）在 CI 阶段拦截潜在 NPE。"),
                    r("数组/字符串下标越界", "IndexOutOfBounds|ArrayIndexOutOfBounds|StringIndexOutOfBounds",
                            "MID",
                            "访问了数组、List 或 String 的非法下标，常见于循环边界写错（<=）、空集合取 get(0)、分页参数越界。",
                            "1. 检查循环条件是否应为 `<` 而非 `<=`；\n" +
                                    "2. 取值前校验 `index >= 0 && index < size()`；\n" +
                                    "3. 空集合取首元素改为 `list.isEmpty() ? null : list.get(0)` 或 Stream.findFirst()；\n" +
                                    "4. 分页接口对 page/size 做边界收敛。"),
                    r("类找不到 ClassNotFoundException", "ClassNotFoundException",
                            "HIGH",
                            "JVM 运行时加载类失败：依赖缺失、版本冲突（同 jar 多版本）、或类在另一个模块中未引入。",
                            "1. 确认报错类所在 jar：`mvn dependency:tree | grep <artifactId>` 检查依赖是否引入及版本；\n" +
                                    "2. 排除冲突版本（dependencyManagement 统一版本）；\n" +
                                    "3. 容器部署时检查 WEB-INF/lib 或 fat-jar 内是否真的包含该类；\n" +
                                    "4. 反射加载（Class.forName）场景增加 try-catch 与降级逻辑。"),
                    r("类定义未找到 NoClassDefFoundError", "NoClassDefFoundError",
                            "HIGH",
                            "编译期存在但运行期缺失，或类静态初始化失败。常伴随另一支异常：依赖被删、热部署残留、静态块抛异常。",
                            "1. 与 ClassNotFoundException 区分：本异常常表示『编译时在、运行时不在』或静态初始化失败；\n" +
                                    "2. 查看同时间段的 ExceptionInInitializerError / StaticInitializer，先修复静态块问题；\n" +
                                    "3. 检查打包产物完整性（jar 是否被截断）、多 classloader 场景避免跨 loader 共享类；\n" +
                                    "4. 重启应用清理热部署残留后验证。"),
                    r("堆内存溢出 Java heap space", "OutOfMemoryError.*Java heap space|Java heap space",
                            "HIGH",
                            "堆内存耗尽：内存泄漏（集合只增不减、ThreadLocal 未清理）、大结果集（无分页查询/大文件一次性读入）、缓存无上限、流量突增。",
                            "1. 确认 JVM 参数已开启 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...`，用 MAT/jvisualvm 分析 dump，找最大对象及引用链；\n" +
                                    "2. 排查无界缓存：本地 Map 缓存改为 Caffeine/Guava 并设置 maximumSize + 过期时间；\n" +
                                    "3. 数据库查询加分页/limit，避免一次加载几十万行；文件/导出改流式处理；\n" +
                                    "4. 若为流量型正当峰值，评估扩容堆（-Xmx）并加限流保护。"),
                    r("GC 开销超限 GC overhead limit exceeded", "GC overhead limit exceeded",
                            "HIGH",
                            "98% 时间用于 GC 却回收不到 2% 空间，堆接近耗尽的先兆，通常随后发生 heap OOM。",
                            "1. 按『Java heap space』方案抓取 heap dump 分析占用大头；\n" +
                                    "2. 临时可加 `-XX:-UseGCOverheadLimit` 掩盖（不推荐，只会推迟崩溃）；\n" +
                                    "3. 检查是否有大量短命大对象分配（日志拼串、深拷贝、大 JSON 序列化）；\n" +
                                    "4. 复核堆大小与实际业务容量是否匹配。"),
                    r("元空间溢出 Metaspace", "OutOfMemoryError.*Metaspace|Metaspace",
                            "HIGH",
                            "类元数据超限：动态代理/反射生成类过多（CGLIB、Groovy、fastjson ASM）、热部署次数过多。",
                            "1. 升高 `-XX:MaxMetaspaceSize`，观察是否继续增长（持续增长=泄漏）；\n" +
                                    "2. 排查动态生成类场景：限制代理类缓存、fastjson 升级并开启 safeMode；\n" +
                                    "3. 热部署频繁的环境改为滚动重启；\n" +
                                    "4. `jcmd <pid> GC.class_stats`（或 jmap -clstats）查看类加载器数量是否异常。"),
                    r("无法创建线程 unable to create new native thread", "unable to create new native thread",
                            "HIGH",
                            "线程数超过系统限制（ulimit nproc）或 JVM 内存不足以再分配栈。常见于代码里 new Thread 无上限、线程池配置过大。",
                            "1. `pstree -p <pid> | wc -l` 或 jstack 统计线程数与线程名，定位疯狂建线程的模块；\n" +
                                    "2. 全部改用受控线程池（固定 core/max + 有界队列），禁止裸 new Thread；\n" +
                                    "3. 调大操作系统限制：`ulimit -u` / systemd TasksMax；\n" +
                                    "4. 适当减小 -Xss（默认 1M）如存在大量小栈线程。"),
                    r("栈溢出 StackOverflowError", "StackOverflowError",
                            "HIGH",
                            "递归未终止、递归深度过大，或序列化循环引用导致无限递归。",
                            "1. 堆栈中重复的帧即递归点，检查终止条件是否可达（集合/树结构是否有环）；\n" +
                                    "2. 深递归改迭代实现，或增大 -Xss（治标）；\n" +
                                    "3. JSON 循环引用使用 @JsonIgnore / @JsonBackReference 切断；\n" +
                                    "4. 分页/树查询注意父子节点互相引用形成环。"),
                    r("数据库连接失败/连接池耗尽",
                            "(?i)connection refused|Communications link failure|Connection is not available|CannotGetJdbcConnection",
                            "HIGH",
                            "数据库不可达或连接池耗尽：DB 宕机/网络隔离、连接数达到 max、慢 SQL 长期占用连接、连接泄漏（借出不还）。",
                            "1. 从应用机 `telnet <db_host> 3306` 验证连通性，检查 DB 服务与防火墙；\n" +
                                    "2. `show processlist` / v$session 查看活跃连接是否打满，是否存在 Sleep 多年的泄漏连接；\n" +
                                    "3. HikariCP 建议配置：maxLifetime 比数据库 wait_timeout 小 30s 以上；leakDetectionThreshold=60000；\n" +
                                    "4. 慢 SQL 侵占连接：开启慢查询日志，加索引或拆分大事务。"),
                    r("SQL 语法/映射错误 BadSqlGrammar|SQLSyntaxError", "(?i)BadSqlGrammar|SQLSyntaxError|Unknown column|doesn't exist in 'field list'",
                            "MID",
                            "SQL 写错或实体与表结构不一致：上线漏执行 DDL、字段拼写错误、MyBatis 映射与列不匹配。",
                            "1. 拿日志中的完整 SQL 到数据库执行复现，定位报错列/表；\n" +
                                    "2. 核对发布单：实体新增字段是否遗漏 DDL（建立『代码+SQL 同包发布』规范）；\n" +
                                    "3. MyBatis 检查 resultMap/别名映射；JPA 检查 @Column 命名策略；\n" +
                                    "4. 上线前用 Flyway/Liquibase 管理表结构变更。"),
                    r("数据库死锁/锁等待", "(?i)Deadlock|Lock wait timeout|try restarting transaction",
                            "HIGH",
                            "并发事务以相反顺序更新同一批行导致死锁，或大事务长时间持锁导致其他事务等待超时。",
                            "1. 提取日志中的 `LATEST DETECTED DEADLOCK` 信息（MySQL SHOW ENGINE INNODB STATUS），确认两个事务持有的锁；\n" +
                                    "2. 统一多表更新的加锁顺序（按主键升序）；\n" +
                                    "3. 缩小事务粒度：把 RPC/文件 IO 移出事务，避免大事务；\n" +
                                    "4. 高并发更新热点行改为『库存扣减Redis+异步落库』或乐观锁版本号。"),
                    r("网络连接拒绝 ConnectException", "java\\.net\\.ConnectException|Connection refused \\(connect failed\\)|(?i)connect timed out",
                            "HIGH",
                            "调用下游服务时目标端口不可达：下游宕机、端口未监听、防火墙拦截、地址配置错误。",
                            "1. 在应用容器内 `telnet/curl` 目标地址验证；\n" +
                                    "2. 核对配置中心的目标地址与端口是否正确（常见环境间配置串用）；\n" +
                                    "3. 下游多实例时配置健康检查摘除故障节点（注册中心/负载均衡）；\n" +
                                    "4. 增加调用超时与重试（幂等前提），避免线程被拖死引发雪崩。"),
                    r("读取超时 SocketTimeout|Read timed out", "SocketTimeoutException|Read timed out",
                            "MID",
                            "下游响应慢超过客户端读超时：慢查询、下游 GC、网络抖动、超时设置过短。",
                            "1. 统计该下游 P99 耗时，将客户端 readTimeout 设为 P99 的 2~3 倍；\n" +
                                    "2. 推动下游优化慢接口（慢 SQL/外部依赖），必要时异步化；\n" +
                                    "3. Feign/RestTemplate 全局超时与重要接口定制超时分开配置；\n" +
                                    "4. 配置有限次重试 + 熔断（Resilience4j/Sentinel）防雪崩。"),
                    r("域名解析失败 UnknownHostException", "UnknownHostException",
                            "MID",
                            "DNS 解析失败或主机名写错：内网 DNS 故障、/etc/hosts 缺失、K8s Service 名错误、域名过期。",
                            "1. 应用容器内 `nslookup <域名>` 验证解析；\n" +
                                    "2. 内网稳定地址建议写入 hosts 或直接配置 IP+端口；\n" +
                                    "3. K8s 检查 Service/Endpoint 是否存在、命名空间是否正确；\n" +
                                    "4. 短域名（相对服务名）仅在服务网格内可用，跨环境调用用全限定名。"),
                    r("SSL 握手失败 SSLHandshakeException", "SSLHandshakeException|PKIX path building failed|certificate_unknown",
                            "MID",
                            "TLS 证书不信任/过期/协议不匹配：自签证书未导入信任库、证书链不完整、TLS 版本不一致。",
                            "1. `openssl s_client -connect host:443 -showcerts` 查看证书链与到期时间；\n" +
                                    "2. 自签/私有 CA：keytool 导入到 JDK cacerts（或 -Djavax.net.ssl.trustStore）；\n" +
                                    "3. 证书快到期的建立监控告警，提前 30 天续期；\n" +
                                    "4. 高版本 JDK 默认禁用 TLS1.0/1.1，与老系统互通需显式开启。"),
                    r("线程池拒绝 RejectedExecutionException", "RejectedExecutionException|ThreadPoolExecutor.*rejected",
                            "MID",
                            "线程池队列满且线程达到 maximumPoolSize，新任务被拒绝：突发流量或任务执行过慢堆积。",
                            "1. 明确拒绝策略语义：CallerRuns 反压、Abort 快速失败、Discard 丢任务（慎用）；\n" +
                                    "2. 队列容量与峰值 QPS×耗时匹配，核心线程数按 CPU/IO 密集合理设置；\n" +
                                    "3. 上游增加限流/削峰（MQ 缓冲）；\n" +
                                    "4. 记录被拒绝任务用于补偿（落库重试）。"),
                    r("并发修改异常 ConcurrentModificationException", "ConcurrentModificationException",
                            "MID",
                            "迭代集合时结构性修改：单线程 foreach 中 remove/add，或多线程共享非线程安全集合。",
                            "1. 单线程删除使用 Iterator.remove() 或 removeIf()；\n" +
                                    "2. 多线程共享集合改为 ConcurrentHashMap / CopyOnWriteArrayList；\n" +
                                    "3. 检查是否有『读时遍历、写时异步修改』的缓存结构；\n" +
                                    "4. 复杂复合操作加锁或改用并发队列。"),
                    r("类型转换异常 ClassCastException", "ClassCastException",
                            "MID",
                            "强转类型不匹配：接口多实现切换、JSON 反序列化为 JSONObject 直接强转 DTO、泛型擦除后强转。",
                            "1. 定位堆栈强转行，打印实际类型 `obj.getClass()`；\n" +
                                    "2. JSON 解析指定目标类型而非强转：`JSON.parseObject(text, OrderDTO.class)`；\n" +
                                    "3. instanceof / 模式匹配先判型；\n" +
                                    "4. 缓存/接口返回 Object 的场景补充类型标记字段。"),
                    r("数字格式异常 NumberFormatException", "NumberFormatException",
                            "LOW",
                            "字符串转数字失败：null/空串/带空格/带单位（如 \"12px\"）/科学计数法格式不匹配。",
                            "1. 转换前 trim 并校验格式（正则 ^-?\\d+(\\.\\d+)?$）；\n" +
                                    "2. 使用返回默认值的工具：`NumberUtils.toInt(s, 0)`；\n" +
                                    "3. 上游传入参数（单号、金额）增加参数校验注解 @Pattern；\n" +
                                    "4. 记录非法原始值便于定位脏数据来源。"),
                    r("JSON 解析异常", "JsonProcessingException|JSONException|MalformedJsonException|JSONException|UnrecognizedPropertyException",
                            "MID",
                            "JSON 格式非法或结构不符：字段类型不匹配、多余/缺失字段、编码问题、上游接口变更。",
                            "1. 打印原始报文定位差异位置（报错行/列）；\n" +
                                    "2. DTO 与上游字段对齐：加 @JsonIgnoreProperties(ignoreUnknown=true) 容忍多余字段；\n" +
                                    "3. 对外接口变更走版本化（/v2）或灰度；\n" +
                                    "4. 网关/日志采集注意报文被截断导致的半截 JSON。"),
                    r("Redis 连接异常 JedisConnection|RedisConnectionFailure|LettuceConnection", "(?i)JedisConnectionException|RedisConnectionFailureException|LettuceConnectionException|RedisConnectionException|NOAUTH|LOADING Redis",
                            "HIGH",
                            "Redis 不可达或认证失败：Redis 宕机/网络隔离、密码变更、maxclients 打满、大数据量阻塞。",
                            "1. 应用机 `redis-cli -h <host> ping` 验证连通与 AUTH；\n" +
                                    "2. 检查 maxclients 与慢日志 SLOWLOG GET，排除大 key/热 key 阻塞；\n" +
                                    "3. 客户端配置连接池上限与超时，避免故障时线程全部阻塞在 Redis；\n" +
                                    "4. 核心链路对 Redis 做降级预案（本地缓存/熔断），Redis 抖动不拖垮主流程。"),
                    r("连接池耗尽 HikariPool timeout", "HikariPool-.*Connection is not available|connection is not available, request timed out|pool exhausted|WaitTimeoutException",
                            "HIGH",
                            "拿连接等待超时：慢 SQL/大事务占满连接、池配置过小、连接泄漏。",
                            "1. 开启 Hikari leakDetectionThreshold=60000 抓未归还连接的堆栈；\n" +
                                    "2. 找慢 SQL（慢查询日志/Druid 监控）优化索引与写法；\n" +
                                    "3. maximumPoolSize 按 `核心数*2 + 磁盘数` 起估，压测校准，勿盲目调大（DB 侧也有上限）；\n" +
                                    "4. 事务内禁止 RPC/睡等待，缩小事务范围。"),
                    r("文件/磁盘 IO 异常", "(?i)No space left on device|FileNotFoundException|Too many open files|DiskFull",
                            "HIGH",
                            "磁盘写满、文件不存在/无权限、文件句柄泄漏。",
                            "1. `df -h` 检查磁盘、`lsof -p <pid> | wc -l` 检查句柄数；\n" +
                                    "2. 磁盘满：清理/轮转日志（logback 配置 maxHistory+totalSizeCap）、导出文件定时清理；\n" +
                                    "3. 句柄泄漏：流用 try-with-resources；`ulimit -n` 调大到 65535+；\n" +
                                    "4. 建立磁盘使用率告警（85% 预警），预留日志压缩任务。"),
                    r("网关 5xx 错误 502/503/504", "(?i)status code:? (502|503|504)|gateway timeout|bad gateway|service unavailable",
                            "HIGH",
                            "网关/负载均衡无法从上游获得正常响应：上游宕机(502)、过载保护(503)、上游响应超时(504)。",
                            "1. 区分类型：502 上游挂/连接被重置，503 过载或维护，504 上游处理超时；\n" +
                                    "2. 检查上游服务健康状态与网关健康检查配置（摘除故障实例）；\n" +
                                    "3. 504 场景按『读取超时』方案优化慢接口，网关超时与下游超时匹配；\n" +
                                    "4. 过载时开启限流/排队，保留容量应对突发。"),
                    r("HTTP 调用 401/403 认证授权失败", "(?i)status code:? (401|403)|unauthorized|access denied|forbidden",
                            "MID",
                            "凭证失效或权限不足：token 过期、密钥轮换、签名算法不一致、IP 白名单未加。",
                            "1. 核对调用凭证（AK/SK、token）是否过期或被轮换，重签发后更新配置中心；\n" +
                                    "2. 签名失败检查：参与签名的参数排序/编码/时间戳偏差（±5min）；\n" +
                                    "3. 403 检查目标系统 IP 白名单与账号权限；\n" +
                                    "4. 凭证集中管理并设置到期提醒。"),
                    r("Kafka 消费异常", "(?i)CommitFailedException|rebalance|RecordTooLargeException|NotLeaderForPartition|RecordAlreadyDeleted",
                            "HIGH",
                            "消费处理耗时超过 max.poll.interval 触发 rebalance、消息过大被拒、Broker 主副本切换。",
                            "1. CommitFailed：缩小单批处理量（max.poll.records）、优化处理耗时或把重逻辑异步化；\n" +
                                    "2. RecordTooLarge：拆分大消息或转存对象存储只传引用；\n" +
                                    "3. 频繁 rebalance 检查消费者组会话超时与心跳、避免长 GC；\n" +
                                    "4. 幂等消费 + 死信队列兜底，避免毒丸消息卡死分区。"),
                    r("方法参数非法 IllegalArgumentException", "IllegalArgumentException",
                            "LOW",
                            "参数不满足方法约定：枚举名不存在、空参数、断言失败（Spring Assert）。",
                            "1. 按堆栈定位断言来源，打印实际入参；\n" +
                                    "2. 对外接口用 @Valid + JSR303 校验提前拦截；\n" +
                                    "3. 枚举解析用安全方法（EnumUtils.getEnum，未命中给默认值）；\n" +
                                    "4. 若来自底层组件（线程池参数、时间格式），检查配置值合法性。"),
                    r("中断异常 InterruptedException", "InterruptedException",
                            "LOW",
                            "线程在阻塞时被中断：优雅停机、超时取消、线程池 shutdown。若被吞掉会丢失中断标记。",
                            "1. 捕获后恢复中断标记：`Thread.currentThread().interrupt();` 再决定退出或记录；\n" +
                                    "2. 不要 catch 后空处理；长任务响应中断尽快退出释放资源；\n" +
                                    "3. 停机场景确认是预期行为（发布/伸缩容），否则排查中断来源；\n" +
                                    "4. 线程池任务用 Future.cancel(true) 配合可中断操作。"),
                    r("自定义业务异常 BusinessException", "(?:\\w+\\.)?(?:Business|Biz|Service)Exception",
                            "MID",
                            "业务校验失败主动抛出（余额不足、状态不允许、重复提交等），通常为预期内异常。",
                            "1. 确认消息中的业务规则是否合理（防误伤正常用户）；\n" +
                                    "2. 预期内异常不应按 ERROR 级别刷日志，降为 WARN 并带上业务码，避免淹没真正的系统异常；\n" +
                                    "3. 高频出现时结合业务数据分析是攻击/爬虫还是流程缺陷；\n" +
                                    "4. 统一异常处理：全局 @RestControllerAdvice 转标准错误码返回。"),
                    r("数据库查询数据不存在 EmptyResultDataAccessException|DoesNotExist|not found",
                            "(?i)EmptyResultDataAccessException|IncorrectResultSizeDataAccessException|NoResultException",
                            "LOW",
                            "getOne/findOne 期望一条却查到 0 条或多条：并发删除、数据不一致、查询条件过松。",
                            "1. 单条查询改为 `findById().orElseThrow(业务异常)`，多条场景用 list 查询取首条；\n" +
                                    "2. 数据不一致场景核对上下游数据同步任务是否失败；\n" +
                                    "3. 查询条件加唯一性约束字段，避免模糊匹配多条；\n" +
                                    "4. 并发删除场景用状态标记（软删除）替代物理删除。")
            ));
            for (int i = 0; i < all.size(); i++) {
                all.get(i).setSortOrder(i * 10);
            }
            return all;
        }
    }
}
