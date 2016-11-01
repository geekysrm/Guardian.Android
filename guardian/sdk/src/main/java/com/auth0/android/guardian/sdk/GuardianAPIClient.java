/*
 * Copyright (c) 2016 Auth0 (http://auth0.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.auth0.android.guardian.sdk;

import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;

import com.auth0.android.guardian.sdk.networking.Request;
import com.auth0.android.guardian.sdk.networking.RequestFactory;
import com.auth0.jwt.Algorithm;
import com.auth0.jwt.JWTSigner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class GuardianAPIClient {

    private static final int JWT_EXP_SECS = 5 * 60; // 5 mins

    private final RequestFactory requestFactory;
    private final HttpUrl baseUrl;

    GuardianAPIClient(RequestFactory requestFactory, HttpUrl baseUrl) {
        this.requestFactory = requestFactory;
        this.baseUrl = baseUrl;
    }

    String getUrl() {
        return baseUrl.toString();
    }

    /**
     * Creates an enroll. When successful, returns extra data about the new Enrollment, including
     * the token that can be used to update the push notification settings and to un-enroll this
     * device.
     * This device will now be available as a Guardian second factor.
     *
     * @param enrollmentTicket the enrollment ticket obtained from a Guardian QR code or enrollment
     *                         email
     * @param deviceIdentifier the local identifier that uniquely identifies the android device
     * @param deviceName this device's name
     * @param gcmToken the GCM token required to send push notifications to this device
     * @param publicKey the RSA public key to associate with the enrollment
     * @return a request to execute or start
     * @throws IllegalArgumentException when the public key is not an RSA public key
     */
    @NonNull
    public GuardianAPIRequest<Map<String, Object>> enroll(@NonNull String enrollmentTicket,
                                                          @NonNull String deviceIdentifier,
                                                          @NonNull String deviceName,
                                                          @NonNull String gcmToken,
                                                          @NonNull PublicKey publicKey) {
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        return requestFactory
                .<Map<String, Object>>newRequest("POST", baseUrl.resolve("api/enroll"), type)
                .setHeader("Authorization", String.format("Ticket id=\"%s\"", enrollmentTicket))
                .setParameter("identifier", deviceIdentifier)
                .setParameter("name", deviceName)
                .setParameter("push_credentials", createPushCredentials(gcmToken))
                .setParameter("public_key", createJWK(publicKey));
    }

    private static Map<String, String> createJWK(@NonNull PublicKey publicKey) {
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new IllegalArgumentException("Only RSA keys are supported");
        }
        RSAPublicKey rsaPublicKey = (RSAPublicKey) publicKey;
        Map<String, String> jwk = new HashMap<>(5);
        jwk.put("kty", "RSA");
        jwk.put("alg", "RS256");
        jwk.put("use", "sig");
        jwk.put("e", "AQAB");
        jwk.put("n", Base64.encodeToString(rsaPublicKey.getModulus().toByteArray(),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
        return jwk;
    }

    private static Map<String, String> createPushCredentials(@Nullable String gcmToken) {
        if (gcmToken == null) {
            return null;
        }

        Map<String, String> pushCredentials = new HashMap<>(2);
        pushCredentials.put("service", "GCM");
        pushCredentials.put("token", gcmToken);
        return pushCredentials;
    }

    /**
     * Returns the "device_account_token" that can be used to update the push notification settings
     * and also to un-enroll the device account
     * This endpoint should only be called once (when starting the enroll)
     *
     * @param enrollmentTransactionId the enrollment transaction id
     * @return a request to execute
     */
    public GuardianAPIRequest<String> getDeviceToken(@NonNull String enrollmentTransactionId) {
        Type type = new TypeToken<Map<String, String>>() {}.getType();
        Request<Map<String, String>> request = requestFactory
                .<Map<String, String>>newRequest("POST", baseUrl.resolve("api/enrollment-info"), type)
                .setParameter("enrollment_tx_id", enrollmentTransactionId);
        return new DeviceTokenRequest(request);
    }

    /**
     * Allows an authentication request using the enrollment's private key
     *
     * @param txToken the auth transaction token
     * @param deviceIdentifier the local device identifier
     * @param challenge the one time password
     * @param privateKey the private key used to sign the payload
     * @return a request to execute
     */
    public GuardianAPIRequest<Void> allow(@NonNull String txToken,
                                          @NonNull String deviceIdentifier,
                                          @NonNull String challenge,
                                          @NonNull PrivateKey privateKey) {
        String jwt = createJWT(privateKey, deviceIdentifier, challenge, true, null);
        Type type = new TypeToken<Void>() {}.getType();
        return requestFactory
                .<Void>newRequest("POST", baseUrl.resolve("api/resolve-transaction"), type)
                .setBearer(txToken)
                .setParameter("challengeResponse", jwt);
    }

    /**
     * Rejects an authentication request using the enrollment's private key, indicating a reason
     *
     * @param txToken the auth transaction token
     * @param deviceIdentifier the local device identifier
     * @param challenge the one time password
     * @param privateKey the private key used to sign the payload
     * @param reason the reject reason
     * @return a request to execute
     */
    public GuardianAPIRequest<Void> reject(@NonNull String txToken,
                                           @NonNull String deviceIdentifier,
                                           @NonNull String challenge,
                                           @NonNull PrivateKey privateKey,
                                           @Nullable String reason) {
        String jwt = createJWT(privateKey, deviceIdentifier, challenge, false, reason);
        Type type = new TypeToken<Void>() {}.getType();
        return requestFactory
                .<Void>newRequest("POST", baseUrl.resolve("api/resolve-transaction"), type)
                .setBearer(txToken)
                .setParameter("challengeResponse", jwt);
    }

    /**
     * Rejects an authentication request using the enrollment's private key
     *
     * @param txToken the auth transaction token
     * @param deviceIdentifier the local device identifier
     * @param challenge the one time password
     * @param privateKey the private key used to sign the payload
     * @return a request to execute
     */
    public GuardianAPIRequest<Void> reject(@NonNull String txToken,
                                           @NonNull String deviceIdentifier,
                                           @NonNull String challenge,
                                           @NonNull PrivateKey privateKey) {
        return reject(txToken, deviceIdentifier, challenge, privateKey, null);
    }

    private String createJWT(@NonNull PrivateKey privateKey,
                             @NonNull String deviceIdentifier,
                             @NonNull String challenge,
                             boolean accepted,
                             @Nullable String reason) {
        JWTSigner.Options options = new JWTSigner.Options();
        options.setAlgorithm(Algorithm.RS256);
        options.setIssuedAt(true);
        options.setExpirySeconds(JWT_EXP_SECS);
        Map<String, Object> claims = new HashMap<>();
        claims.put("aud", getUrl());
        claims.put("iss", deviceIdentifier);
        claims.put("sub", challenge);
        claims.put("auth0.guardian.type", "push_notification");
        claims.put("auth0.guardian.accepted", accepted);
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        claims.put("jti", Base64.encodeToString(nonce, Base64.DEFAULT));
        if (reason != null) {
            claims.put("auth0.guardian.reason", reason);
        }
        JWTSigner jwtSigner = new JWTSigner(privateKey);
        return jwtSigner.sign(claims, options);
    }

    /**
     * Allows an authentication request
     *
     * @param txToken the auth transaction token
     * @param otpCode the one time password
     * @return a request to execute
     * @see #reject(String, String)
     * @see #reject(String, String, String)
     */
    public GuardianAPIRequest<Void> allow(@NonNull String txToken, @NonNull String otpCode) {
        Type type = new TypeToken<Void>() {}.getType();
        return requestFactory
                .<Void>newRequest("POST", baseUrl.resolve("api/verify-otp"), type)
                .setBearer(txToken)
                .setParameter("type", "push_notification")
                .setParameter("code", otpCode);
    }

    /**
     * Rejects an authentication request indicating a reason
     *
     * @param txToken the auth transaction token
     * @param otpCode the one time password
     * @param reason  the reject reason
     * @return a request to execute
     * @see #reject(String, String)
     * @see #allow(String, String)
     */
    public GuardianAPIRequest<Void> reject(@NonNull String txToken,
                                           @NonNull String otpCode,
                                           @Nullable String reason) {
        Type type = new TypeToken<Void>() {}.getType();
        return requestFactory
                .<Void>newRequest("POST", baseUrl.resolve("api/reject-login"), type)
                .setBearer(txToken)
                .setParameter("code", otpCode)
                .setParameter("reason", reason);
    }

    /**
     * Rejects an authentication request
     *
     * @param txToken the auth transaction token
     * @param otpCode the one time password
     * @return a request to execute
     * @see #reject(String, String, String)
     * @see #allow(String, String)
     */
    public GuardianAPIRequest<Void> reject(@NonNull String txToken, @NonNull String otpCode) {
        return reject(txToken, otpCode, null);
    }

    /**
     * Returns an API client to create, update or delete a device
     *
     * @param id    the device id
     * @param token the device token
     * @return an API client for the device
     */
    public DeviceAPIClient device(@NonNull String id, @NonNull String token) {
        return new DeviceAPIClient(requestFactory, baseUrl, id, token);
    }

    public static class Builder {

        private HttpUrl baseUrl;
        private OkHttpClient client;
        private Gson gson;

        public Builder baseUrl(@NonNull String baseUrl) {
            this.baseUrl = HttpUrl.parse(baseUrl);
            if (this.baseUrl == null) {
                throw new IllegalArgumentException("Cannot use an invalid HTTP or HTTPS url: " + baseUrl);
            }
            return this;
        }

        public Builder client(@NonNull OkHttpClient client) {
            this.client = client;
            return this;
        }

        public Builder gson(@NonNull Gson gson) {
            this.gson = gson;
            return this;
        }

        public GuardianAPIClient build() {
            if (baseUrl == null) {
                throw new IllegalArgumentException("baseUrl cannot be null");
            }

            if (client == null) {
                final OkHttpClient.Builder builder = new OkHttpClient.Builder();

                builder.addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        okhttp3.Request originalRequest = chain.request();
                        okhttp3.Request requestWithUserAgent = originalRequest.newBuilder()
                                .header("Accept-Language",
                                        Locale.getDefault().toString())
                                .header("User-Agent",
                                        String.format("GuardianSDK/%s(%s) Android %s",
                                                BuildConfig.VERSION_NAME,
                                                BuildConfig.VERSION_CODE,
                                                Build.VERSION.RELEASE))
                                .build();
                        return chain.proceed(requestWithUserAgent);
                    }
                });

                client = builder.build();
            }

            if (gson == null) {
                gson = new GsonBuilder()
                        .create();
            }

            RequestFactory requestFactory = new RequestFactory(gson, client);

            return new GuardianAPIClient(requestFactory, baseUrl);
        }
    }
}
