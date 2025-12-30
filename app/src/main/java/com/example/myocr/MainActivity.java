package com.example.myocr;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private ImageView ivIdCard;
    private Button btnScan;
    private TextView tvResult;

    private Uri imageUri;
    private File outputImage;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ivIdCard = findViewById(R.id.iv_id_card);
        btnScan = findViewById(R.id.btn_scan);
        tvResult = findViewById(R.id.tv_result);

        btnScan.setOnClickListener(v -> checkPermissionAndCamera());
    }

    private void checkPermissionAndCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    try {
                        // 1. 获取图片
                        Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
                        ivIdCard.setImageBitmap(bitmap);

                        // 2. 图片转 Base64 (使用 Base64Util)
                        String base64Str = imageToBase64(bitmap);

                        // 3. 封装 JSON 参数
                        Map<String, Object> params = new HashMap<>();
                        params.put("ImageBase64", base64Str);
                        params.put("CardSide", "FRONT");

                        Gson gson = new Gson();
                        String jsonPayload = gson.toJson(params);

                        Toast.makeText(this, "正在上传识别...", Toast.LENGTH_SHORT).show();

                        // 4. 发起网络请求 (使用 TencentHttpUtil)
                        TencentHttpUtil.getIdCardDetails(jsonPayload, new TencentHttpUtil.SimpleCallBack() {
                            @Override
                            public void onSuccess(String response) {
                                parseResult(response);
                            }

                            @Override
                            public void onFailure(String error) {
                                Toast.makeText(MainActivity.this, "识别失败: " + error, Toast.LENGTH_LONG).show();
                                Log.e("OCR_ERROR", error);
                            }
                        });

                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
    );

    // 解析腾讯云返回的 JSON
    // 替换 MainActivity 中的 parseResult 方法
    private void parseResult(String response) {
        try {
            // 1. 手动解析第一层 JSON，获取 "Response" 对象
            org.json.JSONObject rootObject = new org.json.JSONObject(response);

            if (rootObject.has("Response")) {
                org.json.JSONObject responseObj = rootObject.getJSONObject("Response");

                if (responseObj.has("Error")) {
                    String errorMsg = responseObj.getJSONObject("Error").getString("Message");
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(MainActivity.this, "云端报错: " + errorMsg, Toast.LENGTH_LONG).show()
                    );
                    return;
                }

                // 2. 将 "Response" 里面的内容转成 IdentifyResult 对象
                Gson gson = new Gson();

                IdentifyResult result = gson.fromJson(responseObj.toString(), IdentifyResult.class);

                new Handler(Looper.getMainLooper()).post(() -> {
                    String message = "识别成功！\n" +
                            "姓名：" + result.getName() + "\n" +
                            "性别：" + result.getSex() + "\n" +
                            "民族：" + result.getNation() + "\n" +
                            "身份证号：" + result.getId();

                    tvResult.setText(message);

                    Toast.makeText(MainActivity.this, "识别完成", Toast.LENGTH_SHORT).show();
                });
            }

        } catch (Exception e) {
            new Handler(Looper.getMainLooper()).post(() ->
                    tvResult.setText("识别失败，请重试")
            );
        }
    }

    // 辅助方法：图片压缩转 Base64
    private String imageToBase64(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        // 质量压缩到 80%，防止图片过大导致请求超时
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64Util.encode(byteArray);
    }

    private void openCamera() {
        outputImage = new File(getExternalCacheDir(), "id_card_temp.jpg");
        try {
            if (outputImage.exists()) outputImage.delete();
            outputImage.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (Build.VERSION.SDK_INT >= 24) {
            imageUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", outputImage);
        } else {
            imageUri = Uri.fromFile(outputImage);
        }

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        try {
            cameraLauncher.launch(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}