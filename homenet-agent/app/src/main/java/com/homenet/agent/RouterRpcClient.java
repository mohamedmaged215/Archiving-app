package com.homenet.agent;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RouterRpcClient {
    private static final String ZERO_STACK = "0,0,0,0,0,0";
    private static final Pattern RECORD_HEADER = Pattern.compile("^\\[([^]]+)](\\d+)$");
    private final String baseUrl;
    private final String cookie;

    RouterRpcClient(RouterCredentialsStore.Credentials credentials) {
        String address = credentials.address.trim();
        if (!address.startsWith("http://") && !address.startsWith("https://")) address = "http://" + address;
        while (address.endsWith("/")) address = address.substring(0, address.length() - 1);
        baseUrl = address;
        String token = Base64.encodeToString(
                (credentials.username + ":" + credentials.password).getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );
        cookie = "Authorization=Basic " + token;
    }

    void authenticate() throws IOException {
        HttpResponse response = request("GET", baseUrl + "/", null);
        if (response.status != 200 || (!response.body.contains("mainFrame") && !response.body.contains("MenuRpm"))) {
            throw new AuthenticationException("لم يقبل الراوتر بيانات الدخول المحفوظة.");
        }
    }

    List<TrafficStat> readTrafficStats() throws IOException {
        List<Operation> operations = new ArrayList<>();
        operations.add(Operation.get("STAT_CFG", "enable", "interval"));
        operations.add(Operation.list("STAT_ENTRY"));
        RpcResponse response = execute(operations);
        List<TrafficStat> result = new ArrayList<>();
        for (Record record : response.recordsFor(1)) {
            String mac = normalizeMac(record.value("macAddress"));
            if (mac.isEmpty()) continue;
            result.add(new TrafficStat(
                    numericIp(record.value("ipAddress")),
                    mac,
                    positiveLong(record.value("totalPkts")),
                    positiveLong(record.value("totalBytes")),
                    positiveLong(record.value("currPkts")),
                    positiveLong(record.value("currBytes"))
            ));
        }
        return result;
    }

    List<DeviceIdentity> readDhcpClients() throws IOException {
        RpcResponse response = execute(single(Operation.list(
                "LAN_HOST_ENTRY",
                "leaseTimeRemaining", "MACAddress", "hostName", "IPAddress"
        )));
        List<DeviceIdentity> result = new ArrayList<>();
        for (Record record : response.recordsFor(0)) {
            if (positiveLong(record.value("leaseTimeRemaining")) <= 0) continue;
            String mac = normalizeMac(record.value("MACAddress"));
            String ip = record.value("IPAddress").trim();
            if (!mac.isEmpty() && !ip.isEmpty()) {
                result.add(new DeviceIdentity(ip, mac, record.value("hostName").trim()));
            }
        }
        return result;
    }

    void setInternetAccess(String macAddress, String deviceId, boolean internetEnabled) throws IOException {
        String mac = normalizeMac(macAddress);
        if (mac.isEmpty()) throw new RouterProtocolException("عنوان MAC للجهاز غير صالح.");
        String suffix = mac.replace(":", "");
        suffix = suffix.substring(Math.max(0, suffix.length() - 6));
        String hostName = "HN_" + suffix;
        String id = deviceId == null ? "" : deviceId.replace("-", "").toUpperCase(Locale.US);
        if (id.length() > 8) id = id.substring(0, 8);
        if (id.isEmpty()) id = suffix;
        String ruleName = "HN_B_" + id;

        ControlState state = readControlState();
        Record existingRule = findRule(state.rules, ruleName);
        if (internetEnabled) {
            if (existingRule != null && !"0".equals(existingRule.value("enable"))) {
                execute(single(Operation.set("RULE", existingRule.stack, ruleAttributes(ruleName,
                        existingRule.value("internalHostRef"), false))));
            }
            return;
        }

        if (!"1".equals(state.firewall.value("enable")) || !"0".equals(state.firewall.value("defaultAction"))) {
            LinkedHashMap<String, String> safePolicy = new LinkedHashMap<>();
            safePolicy.put("enable", "1");
            safePolicy.put("defaultAction", "0");
            execute(single(Operation.set("FIREWALL", ZERO_STACK, safePolicy)));
        }

        Record host = findHostByMac(state.hosts, mac);
        if (host == null) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put("type", "1");
            values.put("entryName", hostName);
            values.put("mac", mac);
            execute(single(Operation.add("INTERNAL_HOST", values)));
            state = readControlState();
            host = findHostByMac(state.hosts, mac);
            if (host == null) throw new RouterProtocolException("لم يؤكد الراوتر حفظ تعريف الجهاز.");
        }
        String savedHostName = host.value("entryName");
        existingRule = findRule(state.rules, ruleName);
        if (existingRule == null) {
            execute(single(Operation.add("RULE", ruleAttributes(ruleName, savedHostName, true))));
        } else {
            execute(single(Operation.set("RULE", existingRule.stack, ruleAttributes(ruleName, savedHostName, true))));
        }
    }

    private ControlState readControlState() throws IOException {
        List<Operation> operations = new ArrayList<>();
        operations.add(Operation.list("RULE"));
        operations.add(Operation.get("FIREWALL", "enable", "defaultAction"));
        operations.add(Operation.list("INTERNAL_HOST"));
        RpcResponse response = execute(operations);
        List<Record> firewallRecords = response.recordsFor(1);
        if (firewallRecords.isEmpty()) throw new RouterProtocolException("لم يرسل الراوتر حالة Access Control.");
        return new ControlState(response.recordsFor(0), firewallRecords.get(0), response.recordsFor(2));
    }

    private LinkedHashMap<String, String> ruleAttributes(String ruleName, String hostName, boolean enabled) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("ruleName", ruleName);
        values.put("internalHostRef", hostName);
        values.put("externalHostRef", "");
        values.put("scheduleRef", "");
        values.put("action", "1");
        values.put("enable", enabled ? "1" : "0");
        values.put("direction", "1");
        values.put("protocol", "3");
        return values;
    }

    private Record findHostByMac(List<Record> hosts, String mac) {
        for (Record host : hosts) {
            if (!"1".equals(host.value("isParentCtrl")) && mac.equals(normalizeMac(host.value("mac")))) return host;
        }
        return null;
    }

    private Record findRule(List<Record> rules, String ruleName) {
        for (Record rule : rules) {
            if (!"1".equals(rule.value("isParentCtrl")) && ruleName.equals(rule.value("ruleName"))) return rule;
        }
        return null;
    }

    private RpcResponse execute(List<Operation> operations) throws IOException {
        StringBuilder query = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < operations.size(); i++) {
            Operation operation = operations.get(i);
            if (i > 0) query.append('&');
            query.append(operation.type);
            body.append('[').append(operation.oid).append('#').append(operation.stack)
                    .append('#').append(ZERO_STACK).append(']').append(i).append(',')
                    .append(operation.attributes.size()).append("\r\n");
            for (String attribute : operation.attributes) body.append(attribute).append("\r\n");
        }
        HttpResponse http = request("POST", baseUrl + "/cgi?" + query, body.toString());
        if (http.status == 401 || http.status == 403 || http.body.contains("pcPassword")) {
            throw new AuthenticationException("انتهت جلسة الراوتر أو تغيرت بيانات الدخول.");
        }
        if (http.status < 200 || http.status >= 300) throw new IOException("الراوتر أعاد HTTP " + http.status);
        RpcResponse response = RpcResponse.parse(http.body);
        if (response.errorCode != 0) throw new RouterProtocolException("رفض الراوتر العملية (" + response.errorCode + ").");
        return response;
    }

    private HttpResponse request(String method, String target, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7_000);
        connection.setReadTimeout(12_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cookie", cookie);
        connection.setRequestProperty("Referer", baseUrl + "/mainFrame.htm");
        connection.setRequestProperty("User-Agent", "HomeNet-Agent/0.6 Android");
        connection.setRequestProperty("Accept", "text/html,text/plain,*/*");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = readAll(stream);
        connection.disconnect();
        return new HttpResponse(status, responseBody);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }

    private static List<Operation> single(Operation operation) {
        List<Operation> result = new ArrayList<>();
        result.add(operation);
        return result;
    }

    private static long positiveLong(String value) {
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String numericIp(String value) {
        try {
            long number = Long.parseLong(value.trim()) & 0xffffffffL;
            return ((number >>> 24) & 255) + "." + ((number >>> 16) & 255) + "." +
                    ((number >>> 8) & 255) + "." + (number & 255);
        } catch (Exception ignored) {
            return value == null ? "" : value.trim();
        }
    }

    private static String normalizeMac(String value) {
        if (value == null) return "";
        String compact = value.replace("-", "").replace(":", "").trim().toUpperCase(Locale.US);
        if (!compact.matches("[0-9A-F]{12}")) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < compact.length(); i += 2) {
            if (result.length() > 0) result.append(':');
            result.append(compact, i, i + 2);
        }
        return result.toString();
    }

    static final class TrafficStat {
        final String ip;
        final String mac;
        final long totalPackets;
        final long totalBytes;
        final long currentPackets;
        final long currentBytes;

        TrafficStat(String ip, String mac, long totalPackets, long totalBytes, long currentPackets, long currentBytes) {
            this.ip = ip;
            this.mac = mac;
            this.totalPackets = totalPackets;
            this.totalBytes = totalBytes;
            this.currentPackets = currentPackets;
            this.currentBytes = currentBytes;
        }
    }

    static final class DeviceIdentity {
        final String ip;
        final String mac;
        final String name;

        DeviceIdentity(String ip, String mac, String name) {
            this.ip = ip;
            this.mac = mac;
            this.name = name;
        }
    }

    static class RouterProtocolException extends IOException {
        RouterProtocolException(String message) { super(message); }
    }

    static final class AuthenticationException extends RouterProtocolException {
        AuthenticationException(String message) { super(message); }
    }

    private static final class ControlState {
        final List<Record> rules;
        final Record firewall;
        final List<Record> hosts;

        ControlState(List<Record> rules, Record firewall, List<Record> hosts) {
            this.rules = rules;
            this.firewall = firewall;
            this.hosts = hosts;
        }
    }

    private static final class HttpResponse {
        final int status;
        final String body;

        HttpResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class Operation {
        final int type;
        final String oid;
        final String stack;
        final List<String> attributes;

        Operation(int type, String oid, String stack, List<String> attributes) {
            this.type = type;
            this.oid = oid;
            this.stack = stack;
            this.attributes = attributes;
        }

        static Operation get(String oid, String... names) { return new Operation(1, oid, ZERO_STACK, strings(names)); }
        static Operation list(String oid, String... names) { return new Operation(5, oid, ZERO_STACK, strings(names)); }
        static Operation add(String oid, LinkedHashMap<String, String> values) { return new Operation(3, oid, ZERO_STACK, pairs(values)); }
        static Operation set(String oid, String stack, LinkedHashMap<String, String> values) { return new Operation(2, oid, stack, pairs(values)); }

        private static List<String> strings(String... values) {
            List<String> result = new ArrayList<>();
            if (values != null) for (String value : values) result.add(value);
            return result;
        }

        private static List<String> pairs(LinkedHashMap<String, String> values) {
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : values.entrySet()) result.add(entry.getKey() + "=" + entry.getValue());
            return result;
        }
    }

    private static final class Record {
        final String stack;
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        Record(String stack) { this.stack = stack; }

        String value(String name) {
            String value = values.get(name);
            return value == null ? "" : value;
        }
    }

    private static final class RpcResponse {
        final List<Record>[] records;
        int errorCode;

        @SuppressWarnings("unchecked")
        RpcResponse(int operationCount) {
            records = new List[operationCount];
            for (int i = 0; i < operationCount; i++) records[i] = new ArrayList<>();
        }

        List<Record> recordsFor(int index) {
            return index >= 0 && index < records.length ? records[index] : new ArrayList<>();
        }

        static RpcResponse parse(String body) {
            String[] lines = body.replace("\r", "").split("\n");
            int maxIndex = 0;
            for (String line : lines) {
                Matcher matcher = RECORD_HEADER.matcher(line.trim());
                if (matcher.matches() && !"error".equals(matcher.group(1))) {
                    maxIndex = Math.max(maxIndex, Integer.parseInt(matcher.group(2)));
                }
            }
            RpcResponse response = new RpcResponse(maxIndex + 1);
            Record current = null;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                Matcher matcher = RECORD_HEADER.matcher(line);
                if (matcher.matches()) {
                    String stack = matcher.group(1);
                    int index = Integer.parseInt(matcher.group(2));
                    if ("error".equals(stack)) {
                        response.errorCode = index;
                        current = null;
                    } else {
                        current = new Record(stack);
                        if (index < response.records.length) response.records[index].add(current);
                    }
                    continue;
                }
                if (current != null) {
                    int separator = line.indexOf('=');
                    if (separator >= 0) current.values.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            return response;
        }
    }
}
