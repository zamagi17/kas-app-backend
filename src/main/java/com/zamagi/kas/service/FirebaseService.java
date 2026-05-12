package com.zamagi.kas.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class FirebaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FirebaseService.class);
    private static boolean isInitialized = false;

    @Value("${firebase.config.json:}")
    private String firebaseConfigJson;

    public FirebaseService() {
        initializeFirebase();
    }

    /**
     * Inisialisasi Firebase Admin SDK Menggunakan credentials dari environment
     * variable atau file konfigurasi
     */
    private void initializeFirebase() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.getApplicationDefault())
                        .build();

                FirebaseApp.initializeApp(options);

                isInitialized = true;
                LOGGER.info("✅ Firebase Admin SDK berhasil diinisialisasi");

                LOGGER.info("Credential path: {}",
                        System.getenv("GOOGLE_APPLICATION_CREDENTIALS"));

            } else {
                isInitialized = true;
            }

        } catch (IOException e) {
            isInitialized = false;

            LOGGER.error("❌ Gagal menginisialisasi Firebase: {}", e.getMessage());

            LOGGER.error("❌ GOOGLE_APPLICATION_CREDENTIALS: {}",
                    System.getenv("GOOGLE_APPLICATION_CREDENTIALS"));
        }
    }

    /**
     * Cek apakah Firebase sudah ter-initialize dengan benar
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Verifikasi Firebase ID Token dan ambil user info
     *
     * @param idToken Token ID dari Firebase client
     * @return Map berisi email, name, dan uid user
     * @throws Exception jika token invalid atau verifikasi gagal
     */
    public Map<String, String> verifyIdToken(String idToken) throws Exception {
        if (!isInitialized) {
            throw new RuntimeException(
                    "Firebase belum diinisialisasi. "
                    + "Pastikan environment variable FIREBASE_CONFIG_JSON sudah diset dengan service account key JSON. "
                    + "Setup: export FIREBASE_CONFIG_JSON='<service-account-key-json>'"
            );
        }

        try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);

            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("uid", decodedToken.getUid());
            userInfo.put("email", decodedToken.getEmail());
            userInfo.put("name", decodedToken.getName());
            userInfo.put("picture", decodedToken.getPicture());

            LOGGER.debug("✅ Token terverifikasi untuk user: {}", decodedToken.getEmail());
            return userInfo;
        } catch (Exception e) {
            LOGGER.error("❌ Gagal verifikasi Firebase token: {}", e.getMessage());
            throw new RuntimeException("Token ID tidak valid atau sudah expired: " + e.getMessage());
        }
    }
}
