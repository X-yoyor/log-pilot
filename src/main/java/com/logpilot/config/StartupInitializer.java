package com.logpilot.config;

import com.logpilot.domain.LogSource;
import com.logpilot.domain.ServerNode;
import com.logpilot.repo.LogSourceRepository;
import com.logpilot.repo.ServerNodeRepository;
import com.logpilot.service.KnowledgeService;
import com.logpilot.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 启动初始化：内置知识库、默认设置、（可选）演示数据。
 * 演示数据 = 本地服务器 + 指向 ./demo-logs/*.log 的日志源 + 一份含典型异常的示例日志，
 * 便于首次部署立即体验完整流程；正式使用时删除该日志源即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupInitializer implements ApplicationRunner {

    private final KnowledgeService knowledgeService;
    private final SettingService settingService;
    private final ServerNodeRepository serverRepo;
    private final LogSourceRepository sourceRepo;

    @Value("${logpilot.seed-demo:true}")
    private boolean seedDemo;

    @Value("${logpilot.retention-days:30}")
    private int retentionDays;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public void run(ApplicationArguments args) {
        settingService.seedDefaults();
        knowledgeService.seedIfEmpty();

        if (!seedDemo || sourceRepo.count() > 0) {
            return;
        }
        try {
            seedDemoData();
        } catch (Exception e) {
            log.warn("演示数据初始化失败（不影响使用）: {}", e.getMessage());
        }
    }

    private void seedDemoData() throws IOException {
        Path dir = Path.of("demo-logs");
        Files.createDirectories(dir);
        Path demoFile = dir.resolve("app-demo.log");
        if (!Files.exists(demoFile)) {
            Files.write(demoFile, buildDemoLog().getBytes(StandardCharsets.UTF_8));
        }

        ServerNode local = new ServerNode();
        local.setName("本机（演示）");
        local.setType("LOCAL");
        local.setRemark("演示用本地节点，实际使用时添加内网 SSH 服务器");
        local.setEnabled(true);
        local = serverRepo.save(local);

        LogSource source = new LogSource();
        source.setName("演示应用日志");
        source.setMode("LOCAL");
        source.setServerId(local.getId());
        source.setLogPath(demoFile.toAbsolutePath().toString().replace('\\', '/'));
        source.setFormatType("AUTO");
        source.setEncoding("UTF-8");
        source.setIntervalMinutes(5);
        source.setEnabled(true);
        source.setNextCollectTime(LocalDateTime.now().plusSeconds(15));
        source.setRemark("指向 demo-logs/app-demo.log，用于体验完整采集分析流程");
        sourceRepo.save(source);

        log.info("演示数据已创建：本地日志源 -> {}", demoFile.toAbsolutePath());
    }

    /** 生成含典型异常的示例日志（时间戳基于当前时间回溯）。 */
    private String buildDemoLog() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(120);
        StringBuilder sb = new StringBuilder();
        int[] offsets = {1, 3, 4, 7, 12, 15, 19, 23, 27, 30, 36, 41, 47, 53, 60, 66, 72, 80, 88, 95, 103, 110};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int off : offsets) {
            LocalDateTime t = base.plusSeconds(off * 60 + rnd.nextInt(50));
            String ts = t.format(TS);
            switch (off % 8) {
                case 0, 4 -> sb.append(ts).append(" [http-nio-8080-exec-").append(rnd.nextInt(1, 9)).append("] INFO  c.demo.order.OrderService - 订单创建成功 orderId=").append(100000 + rnd.nextInt(9999)).append('\n');
                case 1 -> sb.append(ts).append(" [http-nio-8080-exec-3] ERROR c.demo.order.OrderController - 创建订单失败 orderId=88123\n")
                        .append("java.lang.NullPointerException: Cannot invoke \"com.demo.order.model.Customer.getVipLevel()\" because the return value of \"com.demo.order.OrderService.findCustomer(String)\" is null\n")
                        .append("\tat com.demo.order.OrderService.applyVipDiscount(OrderService.java:142)\n")
                        .append("\tat com.demo.order.OrderController.createOrder(OrderController.java:75)\n")
                        .append("\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n")
                        .append("\tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:205)\n");
                case 2 -> sb.append(ts).append(" [http-nio-8080-exec-5] ERROR c.demo.pay.PaymentClient - 调用支付网关超时 orderNo=20231026").append(rnd.nextInt(1000, 9999)).append("\n")
                        .append("java.net.SocketTimeoutException: Read timed out\n")
                        .append("\tat java.base/java.net.SocketInputStream.socketRead0(Native Method)\n")
                        .append("\tat com.demo.pay.PaymentClient.callGateway(PaymentClient.java:88)\n")
                        .append("\tat com.demo.pay.PaymentService.pay(PaymentService.java:45)\n");
                case 3 -> sb.append(ts).append(" [http-nio-8080-exec-2] ERROR o.a.c.c.C.[.[.[/demo] - Servlet.service() for servlet [dispatcherServlet] in context with path [/demo] threw exception [Request processing failed; nested exception is org.springframework.dao.DataAccessResourceFailureException: JDBC Connection refused; nested exception is com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure] with root cause\n")
                        .append("java.net.ConnectException: Connection refused (Connection refused)\n")
                        .append("\tat java.base/sun.nio.ch.Net.connect0(Native Method)\n")
                        .append("\tat com.zaxxer.hikari.util.DriverDataSource.getConnection(DriverDataSource.java:121)\n");
                case 5 -> sb.append(ts).append(" [pool-3-thread-1] WARN  c.demo.report.ReportTask - 生成报表耗时过长 cost=12481ms size=").append(rnd.nextInt(10000, 90000)).append('\n');
                case 6 -> sb.append(ts).append(" [http-nio-8080-exec-7] ERROR c.demo.user.UserController - 查询用户详情失败 userId=").append(rnd.nextInt(1000, 9999)).append("\n")
                        .append("com.mysql.cj.jdbc.exceptions.SQLException: Lock wait timeout exceeded; try restarting transaction\n")
                        .append("\tat com.mysql.cj.jdbc.ClientPreparedStatement.execute(ClientPreparedStatement.java:370)\n")
                        .append("\tat com.demo.user.UserRepository.findById(UserRepository.java:210)\n");
                case 7 -> sb.append(ts).append(" [scheduling-1] ERROR c.demo.cache.CacheSyncJob - 同步缓存失败\n")
                        .append("redis.clients.jedis.exceptions.JedisConnectionException: Failed to connect to host 10.20.30.40:6379\n")
                        .append("\tat redis.clients.jedis.DefaultParser.parse(DefaultParser.java:102)\n")
                        .append("\tat com.demo.cache.CacheSyncJob.sync(CacheSyncJob.java:55)\n")
                        .append("Caused by: java.net.ConnectException: Connection refused\n");
                default -> sb.append(ts).append(" [main] INFO  com.demo.DemoApplication - Started DemoApplication in 8.352 seconds\n");
            }
        }
        return sb.toString();
    }
}
