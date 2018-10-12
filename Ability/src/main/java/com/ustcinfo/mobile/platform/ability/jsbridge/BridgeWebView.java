package com.ustcinfo.mobile.platform.ability.jsbridge;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@TargetApi(19)
@SuppressLint("SetJavaScriptEnabled")
public class BridgeWebView extends WebView implements WebViewJavascriptBridge {
    String toLoadJs = "WebViewJavascriptBridge.js";
    Map<String, CallBackFunction> responseCallbacks = new HashMap<String, CallBackFunction>();
    Map<String, BridgeHandler> messageHandlers = new HashMap<String, BridgeHandler>();
    BridgeHandler defaultHandler = new DefaultHandler();
    ProgressDialog progressBar = null;

    List<Message> startupMessage = new ArrayList<Message>();
    long uniqueId = 0;

    public BridgeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BridgeWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public BridgeWebView(Context context) {
        super(context);
        init();
    }

    /**
     * @param handler default handler,handle messages send by js without assigned
     *                handler name, if js message has handler name, it will be
     *                handled by named handlers registered by native
     */
    public void setDefaultHandler(BridgeHandler handler) {
        this.defaultHandler = handler;
    }

    @SuppressWarnings("deprecation")
    private void init() {
        this.setVerticalScrollBarEnabled(false);
        this.setHorizontalScrollBarEnabled(false);
        this.getSettings().setJavaScriptEnabled(true);
        //使用localStorage本地存储  
        this.getSettings().setDomStorageEnabled(true);

        //websql数据库
        this.getSettings().setDatabaseEnabled(true);
        String databasePath = BridgeWebView.this.getContext().getDir("databases", Context.MODE_PRIVATE).getPath();
        this.getSettings().setDatabasePath(databasePath);
        if (Build.VERSION.SDK_INT > 10 && Build.VERSION.SDK_INT < 17) {
            this.removeJavascriptInterface("searchBoxJavaBridge_");
        }

        this.removeJavascriptInterface("accessibility");
        this.removeJavascriptInterface("accessibilityTraversal");
        //HTML5离线缓存
//      	String appCachPath = BridgeWebView.this.getContext().getCacheDir().getAbsolutePath();
//      	this.getSettings().setAppCacheEnabled(true);
//      	Log.i("appCach path：", appCachPath);
//      	this.getSettings().setAppCachePath(appCachPath);
//      	this.getSettings().setAllowFileAccess(true); 
//      	this.getSettings().setAppCacheMaxSize(1024*1024*8);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        this.setWebViewClient(new BridgeWebViewClient());
        progressBar = ProgressDialog.show(BridgeWebView.this.getContext(), null, "页面加载中，请稍后..");
    }

    private void handlerReturnData(String url) {
        String functionName = BridgeUtil.getFunctionFromReturnUrl(url);
        CallBackFunction f = responseCallbacks.get(functionName);
        String data = BridgeUtil.getDataFromReturnUrl(url);
        if (f != null) {
            f.onCallBack(data);
            responseCallbacks.remove(functionName);
            return;
        }
    }

    class BridgeWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            try {
                String s = view.getUrl();
                url = URLDecoder.decode(url, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            if (url.startsWith(BridgeUtil.YY_RETURN_DATA)) { // 如果是返回数据
                handlerReturnData(url);
                return true;
            } else if (url.startsWith(BridgeUtil.YY_OVERRIDE_SCHEMA)) { //
                flushMessageQueue();
                return true;
            } else {
                progressBar.show();
                return super.shouldOverrideUrlLoading(view, url);
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            if (toLoadJs != null) {
                BridgeUtil.webViewLoadLocalJs(view, toLoadJs);
            }

            //
            if (startupMessage != null) {
                for (Message m : startupMessage) {
                    dispatchMessage(m);
                }
                startupMessage = null;
            }
            if (progressBar.isShowing()) {
                progressBar.dismiss();

            }
        }

        @Override
        public void onReceivedError(WebView view, int errorCode,
                                    String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            checkCertificate(handler, view.getUrl());

        }
    }


    private void checkCertificate(final SslErrorHandler handler, String url) {
        OkHttpClient.Builder builder;
        try {
            builder = setCertificates(new OkHttpClient.Builder(), getContext().getAssets().open("ustcinfo.cer"));
        } catch (IOException e) {
            builder = new OkHttpClient.Builder();
        }
        Request request = new Request.Builder().url(url)
                .build();
        builder.build().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handler.cancel();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handler.proceed();
            }
        });
    }

    @Override
    public void send(String data) {
        send(data, null);
    }

    @Override
    public void send(String data, CallBackFunction responseCallback) {
        doSend(null, data, responseCallback);
    }

    private void doSend(String handlerName, String data,
                        CallBackFunction responseCallback) {
        Message m = new Message();
        if (!TextUtils.isEmpty(data)) {
            m.setData(data);
        }
        if (responseCallback != null) {
            String callbackStr = String.format(
                    BridgeUtil.CALLBACK_ID_FORMAT,
                    ++uniqueId
                            + (BridgeUtil.UNDERLINE_STR + SystemClock
                            .currentThreadTimeMillis()));
            responseCallbacks.put(callbackStr, responseCallback);
            m.setCallbackId(callbackStr);
        }
        if (!TextUtils.isEmpty(handlerName)) {
            m.setHandlerName(handlerName);
        }
        queueMessage(m);
    }

    private void queueMessage(Message m) {
        if (startupMessage != null) {
            startupMessage.add(m);
        } else {
            dispatchMessage(m);
        }
    }

    public OkHttpClient.Builder setCertificates(OkHttpClient.Builder client, InputStream... certificates) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            int index = 0;
            for (InputStream certificate : certificates) {
                String certificateAlias = Integer.toString(index++);
                keyStore.setCertificateEntry(certificateAlias, certificateFactory.generateCertificate(certificate));

                try {
                    if (certificate != null)
                        certificate.close();
                } catch (IOException e) {
                }
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

            trustManagerFactory.init(keyStore);
            sslContext.init
                    (
                            null,
                            trustManagerFactory.getTrustManagers(),
                            new SecureRandom()
                    );

            client.sslSocketFactory(sslContext.getSocketFactory());
            client.hostnameVerifier((s, sslSession) -> true);


        } catch (Exception e) {
            e.printStackTrace();
        }
        return client;
    }

    private void dispatchMessage(Message m) {
        String messageJson = m.toJson();
        // escape special characters for json string
        messageJson = messageJson.replaceAll("(\\\\)([^utrn])", "\\\\\\\\$1$2");
        messageJson = messageJson.replaceAll("(?<=[^\\\\])(\")", "\\\\\"");
        String javascriptCommand = String.format(
                BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            this.loadUrl(javascriptCommand);
        }
    }

    public void flushMessageQueue() {
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            loadUrl(BridgeUtil.JS_FETCH_QUEUE_FROM_JAVA,
                    new CallBackFunction() {

                        @Override
                        public void onCallBack(String data) {
                            // deserializeMessage
                            List<Message> list = null;
                            try {
                                list = Message.toArrayList(data);
                            } catch (Exception e) {
                                e.printStackTrace();
                                return;
                            }
                            if (list == null || list.size() == 0) {
                                return;
                            }
                            for (int i = 0; i < list.size(); i++) {
                                Message m = list.get(i);
                                String responseId = m.getResponseId();
                                // 是否是response
                                if (!TextUtils.isEmpty(responseId)) {
                                    CallBackFunction function = responseCallbacks
                                            .get(responseId);
                                    String responseData = m.getResponseData();
                                    function.onCallBack(responseData);
                                    responseCallbacks.remove(responseId);
                                } else {
                                    CallBackFunction responseFunction = null;
                                    // if had callbackId
                                    final String callbackId = m.getCallbackId();
                                    if (!TextUtils.isEmpty(callbackId)) {
                                        responseFunction = new CallBackFunction() {
                                            @Override
                                            public void onCallBack(String data) {
                                                Message responseMsg = new Message();
                                                responseMsg
                                                        .setResponseId(callbackId);
                                                responseMsg
                                                        .setResponseData(data);
                                                queueMessage(responseMsg);
                                            }
                                        };
                                    } else {
                                        responseFunction = new CallBackFunction() {
                                            @Override
                                            public void onCallBack(String data) {
                                                // do nothing
                                            }
                                        };
                                    }
                                    BridgeHandler handler;
                                    if (!TextUtils.isEmpty(m.getHandlerName())) {
                                        handler = messageHandlers.get(m
                                                .getHandlerName());
                                    } else {
                                        handler = defaultHandler;
                                    }
                                    if (handler != null)
                                        handler.handler(m.getData(), responseFunction);
                                }
                            }
                        }
                    });
        }
    }

    public void loadUrl(String jsUrl, CallBackFunction returnCallback) {
        this.loadUrl(jsUrl);
        responseCallbacks.put(BridgeUtil.parseFunctionName(jsUrl),
                returnCallback);
    }

    /**
     * register handler,so that javascript can call it
     *
     * @param handlerName
     * @param handler
     */
    public void registerHandler(String handlerName, BridgeHandler handler) {
        if (handler != null) {
            messageHandlers.put(handlerName, handler);
        }
    }

    /**
     * call javascript registered handler
     *
     * @param handlerName
     * @param data
     * @param callBack
     */
    public void callHandler(String handlerName, String data,
                            CallBackFunction callBack) {
        doSend(handlerName, data, callBack);
    }
}
