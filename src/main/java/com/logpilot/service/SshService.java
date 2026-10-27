package com.logpilot.service;

import com.jcraft.jsch.*;
import com.logpilot.common.CryptoSupport;
import com.logpilot.domain.ServerNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.regex.Pattern;

/**
 * SSH/SFTP 通道：无 Agent 采集，每次采集建立短连接（内网低延迟场景开销可接受）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SshService {

    private final CryptoSupport crypto;

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    public static class SshException extends RuntimeException {
        public SshException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Session openSession(ServerNode s) throws JSchException {
        JSch jsch = new JSch();
        if ("KEY".equalsIgnoreCase(s.getAuthType()) && s.getPrivateKey() != null && !s.getPrivateKey().isEmpty()) {
            String prv = crypto.decrypt(s.getPrivateKey());
            byte[] passphrase = null;
            if (s.getPassword() != null && !s.getPassword().isEmpty()) {
                passphrase = crypto.decrypt(s.getPassword()).getBytes(StandardCharsets.UTF_8);
            }
            jsch.addIdentity("logpilot-key-" + s.getId(), prv.getBytes(StandardCharsets.UTF_8), null, passphrase);
        }
        Session session = jsch.getSession(s.getUsername(), s.getHost(), s.getPort() == null ? 22 : s.getPort());
        String pwd = crypto.decrypt(s.getPassword());
        if ("PASSWORD".equalsIgnoreCase(s.getAuthType()) && pwd != null && !pwd.isEmpty()) {
            session.setPassword(pwd);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setTimeout(0);
        session.connect(CONNECT_TIMEOUT_MS);
        return session;
    }

    /** 测试连通性：连接 + 执行 echo。返回耗时 ms，失败抛异常。 */
    public long test(ServerNode s) {
        long start = System.currentTimeMillis();
        Session session = null;
        try {
            session = openSession(s);
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("echo logpilot-ok");
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
            InputStream in = channel.getInputStream();
            channel.connect(CONNECT_TIMEOUT_MS);
            String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            channel.disconnect();
            if (!out.contains("logpilot-ok")) {
                throw new SshException("命令执行失败: " + err.toString(StandardCharsets.UTF_8).trim(), null);
            }
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            throw new SshException(friendlyError(e), e);
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private String friendlyError(Throwable e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.contains("Auth fail") || msg.contains("authentication")) return "认证失败：请检查用户名/密码/私钥";
        if (msg.contains("timeout") || msg.contains("Timed out")) return "连接超时：请检查主机/端口/防火墙";
        if (msg.contains("refused")) return "连接被拒绝：SSH 端口未开放或地址错误";
        if (msg.contains("UnknownHost")) return "未知主机：域名解析失败";
        return msg;
    }

    /** 列出远端目录下匹配正则的普通文件（绝对路径）。 */
    public List<RemoteFile> listFiles(ServerNode s, String dir, Pattern nameRegex) {
        Session session = null;
        ChannelSftp ch = null;
        try {
            session = openSession(s);
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(CONNECT_TIMEOUT_MS);
            List<RemoteFile> result = new ArrayList<>();
            Vector<ChannelSftp.LsEntry> entries = ch.ls(dir);
            for (ChannelSftp.LsEntry e : entries) {
                String fn = e.getFilename();
                if (".".equals(fn) || "..".equals(fn)) continue;
                if (e.getAttrs().isDir() || e.getAttrs().isLink()) continue;
                if (nameRegex != null && !nameRegex.matcher(fn).matches()) continue;
                result.add(new RemoteFile(dir + "/" + fn, e.getAttrs().getSize()));
            }
            return result;
        } catch (Exception e) {
            throw new SshException("列出远端文件失败 " + dir + ": " + friendlyError(e), e);
        } finally {
            if (ch != null) ch.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    /**
     * 从 offset 起读取最多 maxBytes 字节。返回实际读取（可能小于 maxBytes，到达文件末尾）。
     */
    public byte[] readChunk(ServerNode s, String path, long offset, long maxBytes) {
        Session session = null;
        ChannelSftp ch = null;
        try {
            session = openSession(s);
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(CONNECT_TIMEOUT_MS);
            InputStream in = ch.get(path);
            try {
                skipFully(in, offset);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long remaining = maxBytes;
                int n;
                while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                    bos.write(buf, 0, n);
                    remaining -= n;
                }
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            throw new SshException("读取远端文件失败 " + path + ": " + friendlyError(e), e);
        } finally {
            if (ch != null) ch.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = new byte[16384];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) break;
            n -= r;
        }
    }

    public record RemoteFile(String path, long size) {
    }
}
