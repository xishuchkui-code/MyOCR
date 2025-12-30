package com.example.myocr;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TencentHttpUtil {

    private static final String SECRET_ID = BuildConfig.SECRET_ID;
    private static final String SECRET_KEY = BuildConfig.SECRET_KEY;

    private static final String HOST = "ocr.tencentcloudapi.com";
    private static final String SERVICE = "ocr";
    private static final String ACTION = "IDCardOCR";
    private static final String VERSION = "2018-11-19";
    private static final String REGION = "ap-guangzhou";

    // 定义回调接口，用于将结果传回 MainActivity
    public interface SimpleCallBack {
        void onSuccess(String response);
        void onFailure(String error);
    }

    /**
     * 发送 POST 请求（包含 V3 签名逻辑）
     */
    public static void getIdCardDetails(String jsonPayload, SimpleCallBack callBack) {
        new Thread(() -> {
            try {
                // 1. 准备时间戳和日期
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                sdf.setTimeZone(TimeZone.getTimeZone("UTC")); // 必须是 UTC+0
                String date = sdf.format(new Date(Long.parseLong(timestamp) * 1000));

                // 2. 计算 Authorization 签名
                String auth = getAuthTC3(jsonPayload, timestamp, date);

                // 3. 发送 HTTP 请求
                URL url = new URL("https://" + HOST);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setUseCaches(false);
                conn.setConnectTimeout(5000);

                //设置请求头
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Host", HOST);
                conn.setRequestProperty("Authorization", auth);
                conn.setRequestProperty("X-TC-Action", ACTION);
                conn.setRequestProperty("X-TC-Timestamp", timestamp);
                conn.setRequestProperty("X-TC-Version", VERSION);
                conn.setRequestProperty("X-TC-Region", REGION);

                //写入Payload数据
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }

                //获取响应
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    //切换回主线程回调
                    new Handler(Looper.getMainLooper()).post(() -> callBack.onSuccess(response.toString()));
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> callBack.onFailure("服务器错误: " + responseCode));
                }

            } catch (Exception e) {
                e.printStackTrace();
                new Handler(Looper.getMainLooper()).post(() -> callBack.onFailure("请求异常: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * V3 签名核心逻辑
     */
    private static String getAuthTC3(String payload, String timestamp, String date) throws Exception {
        // ************* 步骤 1：拼接规范请求串 *************
        String httpRequestMethod = "POST";
        String canonicalUri = "/";
        String canonicalQueryString = "";
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n" + "host:" + HOST + "\n";
        String signedHeaders = "content-type;host";
        String hashedRequestPayload = sha256Hex(payload);
        String canonicalRequest = httpRequestMethod + "\n" + canonicalUri + "\n" + canonicalQueryString + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedRequestPayload;

        // ************* 步骤 2：拼接待签名字符串 *************
        String algorithm = "TC3-HMAC-SHA256";
        String credentialScope = date + "/" + SERVICE + "/" + "tc3_request";
        String hashedCanonicalRequest = sha256Hex(canonicalRequest);
        String stringToSign = algorithm + "\n" + timestamp + "\n" + credentialScope + "\n" + hashedCanonicalRequest;

        // ************* 步骤 3：计算签名 *************
        byte[] secretDate = hmac256(("TC3" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac256(secretDate, SERVICE);
        byte[] secretSigning = hmac256(secretService, "tc3_request");
        String signature = bytesToHex(hmac256(secretSigning, stringToSign));

        // ************* 步骤 4：拼接 Authorization *************
        return algorithm + " " + "Credential=" + SECRET_ID + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", " + "Signature=" + signature;
    }

    // SHA256 加密
    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(d);
    }

    // HMAC-SHA256 加密
    private static byte[] hmac256(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, mac.getAlgorithm());
        mac.init(secretKeySpec);
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    // 字节转十六进制
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString().toLowerCase();
    }
}