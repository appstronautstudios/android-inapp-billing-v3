/**
 * Copyright 2014 AnjLab
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.anjlab.android.iab.v3;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class BillingProcessor extends BillingBase {
    /**
     * Callback methods where billing events are reported.
     * Apps must implement one of these to construct a BillingProcessor.
     */
    public interface IBillingHandler {
        void onProductPurchased(@NonNull String productId, @Nullable TransactionDetails details);

        void onPurchaseHistoryRestored();

        void onBillingError(int errorCode, @Nullable Throwable error);

        void onBillingInitialized();
    }

    private static final Date DATE_MERCHANT_LIMIT_1; //5th December 2012
    private static final Date DATE_MERCHANT_LIMIT_2; //21st July 2015

    static {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2012, Calendar.DECEMBER, 5);
        DATE_MERCHANT_LIMIT_1 = calendar.getTime();
        calendar.set(2015, Calendar.JULY, 21);
        DATE_MERCHANT_LIMIT_2 = calendar.getTime();
    }

    private static final int PURCHASE_FLOW_REQUEST_CODE = 32459;
    private static final String LOG_TAG = "iabv3";
    private static final String SETTINGS_VERSION = ".v2_6";
    private static final String RESTORE_KEY = ".products.restored" + SETTINGS_VERSION;
    private static final String MANAGED_PRODUCTS_CACHE_KEY = ".products.cache" + SETTINGS_VERSION;
    private static final String SUBSCRIPTIONS_CACHE_KEY = ".subscriptions.cache" + SETTINGS_VERSION;
    private static final String PURCHASE_PAYLOAD_CACHE_KEY = ".purchase.last" + SETTINGS_VERSION;

    private BillingClient billingClient;
    private String contextPackageName;
    private String signatureBase64;
    private BillingCache cachedProducts;
    private BillingCache cachedSubscriptions;
    private IBillingHandler eventHandler;
    private String developerMerchantId;
    private boolean isOneTimePurchasesSupported;
    private boolean isSubsUpdateSupported;
    private boolean isSubscriptionExtraParamsSupported;
    private boolean isOneTimePurchaseExtraParamsSupported;

    private class HistoryInitializationTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... nothing) {
            if (!isPurchaseHistoryRestored()) {
                loadOwnedPurchasesFromGoogle();
                return true;
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean restored) {
            if (restored) {
                setPurchaseHistoryRestored();
                if (eventHandler != null) {
                    eventHandler.onPurchaseHistoryRestored();
                }
            }
            if (eventHandler != null) {
                eventHandler.onBillingInitialized();
            }
        }
    }

    private PurchasesUpdatedListener purchasesUpdatedListener = new PurchasesUpdatedListener() {
        @Override
        public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> list) {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                if (list != null) {
                    for (final Purchase purchase : list) {
                        handlePurchase(purchase);
                    }
                }
            }
        }
    };

    private void handlePurchase(final Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            try {
                // old code from handleActivityResult. Adds the purchase to the cache
                String purchaseData = purchase.getOriginalJson();
                String dataSignature = purchase.getSignature();
                JSONObject purchaseJson = new JSONObject(purchaseData);
                String productId = purchaseJson.getString(Constants.RESPONSE_PRODUCT_ID);
                if (verifyPurchaseSignature(productId, purchaseData, dataSignature)) {
                    String purchaseType = detectPurchaseTypeFromPurchaseResponseData(purchaseJson);
                    BillingCache cache = purchaseType.equals(Constants.PRODUCT_TYPE_SUBSCRIPTION) ? cachedSubscriptions : cachedProducts;
                    cache.put(productId, purchaseData, dataSignature);
                } else {
                    // invalid signature, bail out
                    reportBillingError(Constants.BILLING_ERROR_INVALID_SIGNATURE, null);
                    return;
                }
                // what does this do?
                savePurchasePayload(null);

                if (!purchase.isAcknowledged()) {
                    // purchase not yet acknowledged
                    AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build();
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams, new AcknowledgePurchaseResponseListener() {
                        @Override
                        public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
                            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                                if (eventHandler != null) {
                                    TransactionDetails transactionDetails = getPurchaseTransactionDetails(purchase.getSkus().get(0));
                                    eventHandler.onProductPurchased(purchase.getSkus().get(0), transactionDetails);
                                }
                            } else {
                                if (eventHandler != null) {
                                    eventHandler.onBillingError(billingResult.getResponseCode(), new Throwable(billingResult.getDebugMessage()));
                                }
                            }
                        }
                    });
                } else {
                    // purchase already acknowledged
                    if (eventHandler != null) {
                        eventHandler.onProductPurchased(productId, new TransactionDetails(new PurchaseInfo(purchaseData, dataSignature)));
                    }
                }
            } catch (Exception e) {
                reportBillingError(Constants.BILLING_ERROR_OTHER_ERROR, e);
            }
        }
    }

    /**
     * Returns a new {@link BillingProcessor}, without immediately binding to Play Services. If you use
     * this factory, then you must call {@link #initialize()} afterwards.
     */
    public static BillingProcessor newBillingProcessor(Context context, String licenseKey, IBillingHandler handler) {
        return newBillingProcessor(context, licenseKey, null, handler);
    }

    /**
     * Returns a new {@link BillingProcessor}, without immediately binding to Play Services. If you use
     * this factory, then you must call {@link #initialize()} afterwards.
     */
    public static BillingProcessor newBillingProcessor(Context context, String licenseKey, String merchantId, IBillingHandler handler) {
        return new BillingProcessor(context, licenseKey, merchantId, handler, false);
    }

    public BillingProcessor(Context context, String licenseKey, IBillingHandler handler) {
        this(context, licenseKey, null, handler);
    }

    public BillingProcessor(Context context, String licenseKey, String merchantId, IBillingHandler handler) {
        this(context, licenseKey, merchantId, handler, true);
    }

    private BillingProcessor(Context context, String licenseKey, String merchantId, IBillingHandler handler, boolean bindImmediately) {
        super(context.getApplicationContext());
        signatureBase64 = licenseKey;
        eventHandler = handler;
        contextPackageName = getContext().getPackageName();
        cachedProducts = new BillingCache(getContext(), MANAGED_PRODUCTS_CACHE_KEY);
        cachedSubscriptions = new BillingCache(getContext(), SUBSCRIPTIONS_CACHE_KEY);
        developerMerchantId = merchantId;
        if (bindImmediately) {
            bindPlayServices();
        }
    }

    /**
     * Binds to Play Services. When complete, caller will be notified via
     * {@link IBillingHandler#onBillingInitialized()}.
     */
    public void initialize() {
        bindPlayServices();
    }

    private static Intent getBindServiceIntent() {
        Intent intent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        intent.setPackage("com.android.vending");
        return intent;
    }

    private void bindPlayServices() {
        try {
            billingClient = BillingClient.newBuilder(getContext())
                    .setListener(purchasesUpdatedListener)
                    .enablePendingPurchases()
                    .build();
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                    new HistoryInitializationTask().execute();
                }

                @Override
                public void onBillingServiceDisconnected() {

                }
            });
        } catch (Exception e) {
            reportBillingError(Constants.BILLING_ERROR_BIND_PLAY_STORE_FAILED, e);
        }
    }

    public static boolean isIabServiceAvailable(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> list = packageManager.queryIntentServices(getBindServiceIntent(), 0);
        return list != null && list.size() > 0;
    }

    public void release() {
        if (isInitialized() && billingClient != null) {
            try {
                billingClient.endConnection();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error in release", e);
            }
            billingClient = null;
        }
    }

    public boolean isInitialized() {
        return billingClient != null;
    }

    public boolean isPurchased(String productId) {
        return cachedProducts.includesProduct(productId);
    }

    public boolean isSubscribed(String productId) {
        return cachedSubscriptions.includesProduct(productId);
    }

    public List<String> listOwnedProducts() {
        return cachedProducts.getContents();
    }

    public List<String> listOwnedSubscriptions() {
        return cachedSubscriptions.getContents();
    }

    /**
     * load purchases by type into cache
     *
     * @param type         - purchase type
     * @param cacheStorage - storage
     * @return - true if initialized, false otherwise
     */
    private boolean loadPurchasesByType(String type, final BillingCache cacheStorage) {
        if (!isInitialized()) {
            return false;
        }

        billingClient.queryPurchasesAsync(type, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(@NonNull BillingResult billingResult, @NonNull List<Purchase> list) {
                try {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        cacheStorage.clear();
                        ArrayList<Purchase> purchaseList = new ArrayList<>(list);
                        if (purchaseList.size() > 0) {
                            for (Purchase purchase : purchaseList) {
                                String jsonData = purchase.getOriginalJson();
                                String signature = purchase.getSignature();
                                if (!TextUtils.isEmpty(jsonData)) {
                                    JSONObject jsonObject = new JSONObject(jsonData);
                                    cacheStorage.put(jsonObject.getString(Constants.RESPONSE_PRODUCT_ID),
                                            jsonData,
                                            signature);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    reportBillingError(Constants.BILLING_ERROR_FAILED_LOAD_PURCHASES, e);
                }
            }
        });
        return true;
    }

    /**
     * Attempt to fetch purchases from the server and update our cache if successful
     *
     * @return {@code true} if all retrievals are successful, {@code false} otherwise
     */
    public boolean loadOwnedPurchasesFromGoogle() {
        return loadPurchasesByType(Constants.PRODUCT_TYPE_MANAGED, cachedProducts) &&
                loadPurchasesByType(Constants.PRODUCT_TYPE_SUBSCRIPTION, cachedSubscriptions);
    }

    public boolean purchase(Activity activity, String productId) {
        return purchase(activity, null, productId, Constants.PRODUCT_TYPE_MANAGED, null);
    }

    public boolean purchase(Activity activity, String productId, String developerPayload) {
        return purchase(activity, productId, Constants.PRODUCT_TYPE_MANAGED, developerPayload);
    }

    /***
     * Purchase a product
     *
     * @param activity the activity calling this method
     * @param productId the product id to purchase
     * @param extraParams A bundle object containing extra parameters to pass to
     *                          getBuyIntentExtraParams()
     * @see <a href="https://developer.android.com/google/play/billing/billing_reference.html#getBuyIntentExtraParams">extra
     * params documentation on developer.android.com</a>
     * @return {@code false} if the billing system is not initialized, {@code productId} is empty
     * or if an exception occurs. Will return {@code true} otherwise.
     */
    public boolean purchase(Activity activity, String productId, String developerPayload, Bundle extraParams) {
        if (!isOneTimePurchaseWithExtraParamsSupported(extraParams)) {
            return purchase(activity, productId, developerPayload);
        } else {
            return purchase(activity, null, productId, Constants.PRODUCT_TYPE_MANAGED, developerPayload, extraParams);
        }
    }

    public boolean subscribe(Activity activity, String productId) {
        return purchase(activity, null, productId, Constants.PRODUCT_TYPE_SUBSCRIPTION, null);
    }

    public boolean subscribe(Activity activity, String productId, String developerPayload) {
        return purchase(activity, productId, Constants.PRODUCT_TYPE_SUBSCRIPTION, developerPayload);
    }

    /**
     * Subscribe to a product
     *
     * @param activity    the activity calling this method
     * @param productId   the product id to purchase
     * @param extraParams A bundle object containing extra parameters to pass to getBuyIntentExtraParams()
     * @return {@code false} if the billing system is not initialized, {@code productId} is empty or if an exception occurs.
     * Will return {@code true} otherwise.
     * @see <a href="https://developer.android.com/google/play/billing/billing_reference.html#getBuyIntentExtraParams">extra
     * params documentation on developer.android.com</a>
     */
    public boolean subscribe(Activity activity, String productId, String developerPayload, Bundle extraParams) {
        return purchase(activity,
                null,
                productId,
                Constants.PRODUCT_TYPE_SUBSCRIPTION,
                developerPayload,
                isSubscriptionWithExtraParamsSupported(extraParams) ? extraParams : null);
    }

    public boolean isSubscriptionUpdateSupported() {
        // Avoid calling the service again if this value is true
        if (isSubsUpdateSupported) {
            return true;
        }

        int response = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS_UPDATE).getResponseCode();

        if (response == BillingClient.BillingResponseCode.OK) {
            isSubsUpdateSupported = true;
        }

        return isSubsUpdateSupported;
    }

    /**
     * Check API v7 support for subscriptions
     *
     * @param extraParams
     * @return {@code true} if the current API supports calling getBuyIntentExtraParams() for
     * subscriptions, {@code false} otherwise.
     */
    public boolean isSubscriptionWithExtraParamsSupported(Bundle extraParams) {
        if (isSubscriptionExtraParamsSupported) {
            return true;
        }

        int response = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS_ON_VR).getResponseCode();

        if (response == BillingClient.BillingResponseCode.OK) {
            isSubscriptionExtraParamsSupported = true;
        }

        return isSubscriptionExtraParamsSupported;
    }

    /**
     * Check API v7 support for one-time purchases
     *
     * @param extraParams
     * @return {@code true} if the current API supports calling getBuyIntentExtraParams() for
     * one-time purchases, {@code false} otherwise.
     */
    public boolean isOneTimePurchaseWithExtraParamsSupported(Bundle extraParams) {
        if (isOneTimePurchaseExtraParamsSupported) {
            return true;
        }

        int response = billingClient.isFeatureSupported(BillingClient.FeatureType.IN_APP_ITEMS_ON_VR).getResponseCode();

        if (response == BillingClient.BillingResponseCode.OK) {
            isOneTimePurchaseExtraParamsSupported = true;
        }

        return isOneTimePurchaseExtraParamsSupported;
    }

    /**
     * Change subscription i.e. upgrade or downgrade
     *
     * @param activity     the activity calling this method
     * @param oldProductId passing null or empty string will act the same as {@link #subscribe(Activity, String)}
     * @param productId    the new subscription id
     * @return {@code false} if {@code oldProductId} is not {@code null} AND change subscription
     * is not supported.
     */
    public boolean updateSubscription(Activity activity, String oldProductId, String productId) {
        return updateSubscription(activity, oldProductId, productId, null);
    }

    /**
     * Change subscription i.e. upgrade or downgrade
     *
     * @param activity         the activity calling this method
     * @param oldProductId     passing null or empty string will act the same as {@link #subscribe(Activity, String)}
     * @param productId        the new subscription id
     * @param developerPayload the developer payload
     * @return {@code false} if {@code oldProductId} is not {@code null} AND change subscription
     * is not supported.
     */
    public boolean updateSubscription(Activity activity, String oldProductId, String productId, String developerPayload) {
        List<String> oldProductIds = null;
        if (!TextUtils.isEmpty(oldProductId)) {
            oldProductIds = Collections.singletonList(oldProductId);
        }
        return updateSubscription(activity, oldProductIds, productId, developerPayload);
    }

    /**
     * Change subscription i.e. upgrade or downgrade
     *
     * @param activity      the activity calling this method
     * @param oldProductIds passing null will act the same as {@link #subscribe(Activity, String)}
     * @param productId     the new subscription id
     * @return {@code false} if {@code oldProductIds} is not {@code null} AND change subscription
     * is not supported.
     */
    public boolean updateSubscription(Activity activity, List<String> oldProductIds, String productId) {
        return updateSubscription(activity, oldProductIds, productId, null);
    }

    /**
     * Change subscription i.e. upgrade or downgrade
     *
     * @param activity         the activity calling this method
     * @param oldProductIds    passing null will act the same as {@link #subscribe(Activity, String)}
     * @param productId        the new subscription id
     * @param developerPayload the developer payload
     * @return {@code false} if {@code oldProductIds} is not {@code null} AND change subscription
     * is not supported.
     */
    public boolean updateSubscription(Activity activity, List<String> oldProductIds, String productId, String developerPayload) {
        if (oldProductIds != null && !isSubscriptionUpdateSupported()) {
            return false;
        }
        return purchase(activity, oldProductIds, productId, Constants.PRODUCT_TYPE_SUBSCRIPTION, developerPayload);
    }

    /**
     * @param activity         the activity calling this method
     * @param oldProductIds    passing null will act the same as {@link #subscribe(Activity, String)}
     * @param productId        the new subscription id
     * @param developerPayload the developer payload
     * @param extraParams      A bundle object containing extra parameters to pass to getBuyIntentExtraParams()
     * @return {@code false} if {@code oldProductIds} is not {@code null} AND change subscription
     * is not supported.
     * @see <a href="https://developer.android.com/google/play/billing/billing_reference.html#getBuyIntentExtraParams">extra
     * params documentation on developer.android.com</a>
     */
    public boolean updateSubscription(Activity activity, List<String> oldProductIds, String productId, String developerPayload, Bundle extraParams) {
        if (oldProductIds != null && !isSubscriptionUpdateSupported()) {
            return false;
        }

        // if API v7 is not supported, let's fallback to the previous method
        if (!isSubscriptionWithExtraParamsSupported(extraParams)) {
            return updateSubscription(activity, oldProductIds, productId, developerPayload);
        }

        return purchase(activity,
                oldProductIds,
                productId,
                Constants.PRODUCT_TYPE_SUBSCRIPTION,
                developerPayload,
                extraParams);
    }

    private boolean purchase(Activity activity, String productId, String purchaseType, String developerPayload) {
        return purchase(activity, null, productId, purchaseType, developerPayload);
    }

    private boolean purchase(Activity activity, List<String> oldProductIds, String productId, String purchaseType, String developerPayload) {
        return purchase(activity, oldProductIds, productId, purchaseType, developerPayload, null);
    }

    /**
     * @param activity
     * @param oldProductIds
     * @param productId
     * @param purchaseType
     * @param developerPayload
     * @param extraParamsBundle
     * @return - very basic true false that is NOT related to purchase success. Only returns false if initial params are invalid or BP not initialized
     */
    private boolean purchase(final Activity activity, List<String> oldProductIds, final String productId, String purchaseType, String developerPayload, Bundle extraParamsBundle) {
        if (!isInitialized() || TextUtils.isEmpty(productId) || TextUtils.isEmpty(purchaseType)) {
            return false;
        }
        try {
            String purchasePayload = purchaseType + ":" + productId;
            if (!purchaseType.equals(Constants.PRODUCT_TYPE_SUBSCRIPTION)) {
                purchasePayload += ":" + UUID.randomUUID().toString();
            }
            if (developerPayload != null) {
                purchasePayload += ":" + developerPayload;
            }
            savePurchasePayload(purchasePayload);

            SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
            List<String> skuList = new ArrayList<>();
            skuList.add(productId);
            params.setSkusList(skuList).setType(purchaseType);
            billingClient.querySkuDetailsAsync(params.build(), new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(@NonNull BillingResult billingResult, List<com.android.billingclient.api.SkuDetails> skuDetailsList) {
                    if (skuDetailsList.size() > 0) {
                        // what is this for?
//                        for (int i = 0; i < skuDetailsList.size(); i++) {
//                            skuDetails = skuDetailsList.get(i);
//                            skuDetails.getPrice();
//                            skuDetails.getTitle();
//                            skuDetails.getDescription();
//                            skuDetails.getSku();
//                            skuDetails.getIntroductoryPrice();
//                            skuDetails.getFreeTrialPeriod();
//                        }

                        final com.android.billingclient.api.SkuDetails finalSkuDetails = skuDetailsList.get(0);
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                                        .setSkuDetails(finalSkuDetails)
                                        .build();

                                // attempt to launch billing flow
                                int responseCode = billingClient.launchBillingFlow(activity, billingFlowParams).getResponseCode();

                                // if item already owned reload purchases from google and update client
                                if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                                    if (!isPurchased(productId) && !isSubscribed(productId)) {
                                        loadOwnedPurchasesFromGoogle();
                                    }

                                    TransactionDetails details = getPurchaseTransactionDetails(productId);
                                    if (!checkMerchant(details)) {
                                        reportBillingError(Constants.BILLING_ERROR_INVALID_MERCHANT_ID, null);
                                    }

                                    if (eventHandler != null) {
                                        if (details == null) {
                                            details = getSubscriptionTransactionDetails(productId);
                                        }
                                        eventHandler.onProductPurchased(productId, details);
                                    }
                                }
                            }
                        });
                    } else {
                        reportBillingError(Constants.BILLING_ERROR_INVALID_PRODUCT_ID, null);
                    }
                }
            });
        } catch (Exception e) {
            reportBillingError(Constants.BILLING_ERROR_OTHER_ERROR, e);
            return false;
        }
        return true;
    }

    /**
     * Checks merchant's id validity. If purchase was generated by Freedom alike program it doesn't know
     * real merchant id, unless publisher GoogleId was hacked
     * If merchantId was not supplied function checks nothing
     *
     * @param details TransactionDetails
     * @return boolean
     */
    private boolean checkMerchant(TransactionDetails details) {
        //omit merchant id checking
        if (developerMerchantId == null) {
            return true;
        }
        //newest format applied
        if (details.purchaseInfo.purchaseData.purchaseTime.before(DATE_MERCHANT_LIMIT_1)) {
            return true;
        }
        //newest format applied
        if (details.purchaseInfo.purchaseData.purchaseTime.after(DATE_MERCHANT_LIMIT_2)) {
            return true;
        }
        if (details.purchaseInfo.purchaseData.orderId == null || details.purchaseInfo.purchaseData.orderId.trim().length() == 0) {
            return false;
        }
        int index = details.purchaseInfo.purchaseData.orderId.indexOf('.');
        if (index <= 0) {
            return false; //protect on missing merchant id
        }
        //extract merchant id
        String merchantId = details.purchaseInfo.purchaseData.orderId.substring(0, index);
        return merchantId.compareTo(developerMerchantId) == 0;
    }

    @Nullable
    private TransactionDetails getPurchaseTransactionDetails(String productId, BillingCache cache) {
        PurchaseInfo details = cache.getDetails(productId);
        if (details != null && !TextUtils.isEmpty(details.responseData)) {
            return new TransactionDetails(details);
        }
        return null;
    }

    public void consumePurchase(final String sku) {
        try {
            billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP, new PurchasesResponseListener() {
                @Override
                public void onQueryPurchasesResponse(@NonNull BillingResult billingResult, @NonNull List<Purchase> list) {
                    Purchase purchase = null;
                    for (Purchase p : list) {
                        if (p.getSkus().contains(sku)) {
                            purchase = list.get(0);
                        }
                    }
                    final Purchase fPurchase = purchase;
                    if (purchase == null) {
                        return;
                    } else {
                        ConsumeParams consumeParams = ConsumeParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();

                        ConsumeResponseListener listener = new ConsumeResponseListener() {
                            @Override
                            public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
                                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                                    if (eventHandler != null) {
                                        TransactionDetails transactionDetails = getPurchaseTransactionDetails(fPurchase.getSkus().get(0));
                                        cachedProducts.remove(transactionDetails.productId);
                                        Log.d(LOG_TAG, "Successfully consumed " + transactionDetails.productId + " purchase.");
                                    }
                                } else {
                                    if (eventHandler != null) {
                                        eventHandler.onBillingError(billingResult.getResponseCode(), new Throwable(billingResult.getDebugMessage()));
                                    }
                                }
                            }
                        };

                        billingClient.consumeAsync(consumeParams, listener);
                    }
                }
            });
        } catch (Exception e) {
            reportBillingError(Constants.BILLING_ERROR_CONSUME_FAILED, e);
        }
    }

    private void getSkuDetailsAsync(final ArrayList<String> productIdList, String purchaseType, final SuccessFailListener listener) {
        if (billingClient != null && productIdList != null && productIdList.size() > 0) {
            SkuDetailsParams skuDetailsParams = SkuDetailsParams.newBuilder()
                    .setSkusList(productIdList)
                    .setType(purchaseType)
                    .build();

            billingClient.querySkuDetailsAsync(skuDetailsParams, new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(@NonNull BillingResult billingResult, List<com.android.billingclient.api.SkuDetails> detailsList) {
                    try {
                        ArrayList<SkuDetails> productDetails = new ArrayList<>();
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                            if (detailsList.size() > 0) {
                                for (com.android.billingclient.api.SkuDetails skuDetails : detailsList) {
                                    String jsonData = skuDetails.getOriginalJson();
                                    JSONObject object = new JSONObject(jsonData);
                                    SkuDetails product = new SkuDetails(object);
                                    productDetails.add(product);
                                }
                            }
                            if (listener != null) {
                                listener.success(productDetails);
                            }
                        } else {
                            throw new Exception("billing response code: " + billingResult.getResponseCode());
                        }
                    } catch (Exception e) {
                        if (listener != null) {
                            listener.fail(e);
                        }
                    }
                }
            });
        }
    }

    public void getPurchaseListingDetails(String productId, SuccessFailListener listener) {
        ArrayList<String> productIds = new ArrayList<>();
        productIds.add(productId);
        getSkuDetailsAsync(productIds, Constants.PRODUCT_TYPE_MANAGED, listener);
    }

    public void getPurchaseListingDetails(ArrayList<String> productIdList, SuccessFailListener listener) {
        getSkuDetailsAsync(productIdList, Constants.PRODUCT_TYPE_MANAGED, listener);
    }

    public void getSubscriptionListingDetails(String productId, SuccessFailListener listener) {
        ArrayList<String> productIds = new ArrayList<>();
        productIds.add(productId);
        getSkuDetailsAsync(productIds, Constants.PRODUCT_TYPE_SUBSCRIPTION, listener);
    }

    public void getSubscriptionListingDetails(ArrayList<String> productIdList, SuccessFailListener listener) {
        getSkuDetailsAsync(productIdList, Constants.PRODUCT_TYPE_SUBSCRIPTION, listener);
    }

    @Nullable
    public TransactionDetails getPurchaseTransactionDetails(String productId) {
        return getPurchaseTransactionDetails(productId, cachedProducts);
    }

    @Nullable
    public TransactionDetails getSubscriptionTransactionDetails(String productId) {
        return getPurchaseTransactionDetails(productId, cachedSubscriptions);
    }

    private String detectPurchaseTypeFromPurchaseResponseData(JSONObject purchase) {
        String purchasePayload = getPurchasePayload();
        // regular flow, based on developer payload
        if (!TextUtils.isEmpty(purchasePayload) && purchasePayload.startsWith(Constants.PRODUCT_TYPE_SUBSCRIPTION)) {
            return Constants.PRODUCT_TYPE_SUBSCRIPTION;
        }
        // backup check for the promo codes (no payload available)
        if (purchase != null && purchase.has(Constants.RESPONSE_AUTO_RENEWING)) {
            return Constants.PRODUCT_TYPE_SUBSCRIPTION;
        }
        return Constants.PRODUCT_TYPE_MANAGED;
    }

    private boolean verifyPurchaseSignature(String productId, String purchaseData, String dataSignature) {
        try {
            /*
             * Skip the signature check if the provided License Key is NULL and return true in order to
             * continue the purchase flow
             */
            return TextUtils.isEmpty(signatureBase64) ||
                    Security.verifyPurchase(productId, signatureBase64, purchaseData, dataSignature);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isValidTransactionDetails(TransactionDetails transactionDetails) {
        return verifyPurchaseSignature(transactionDetails.purchaseInfo.purchaseData.productId,
                transactionDetails.purchaseInfo.responseData,
                transactionDetails.purchaseInfo.signature) &&
                checkMerchant(transactionDetails);
    }

    private boolean isPurchaseHistoryRestored() {
        return loadBoolean(getPreferencesBaseKey() + RESTORE_KEY, false);
    }

    private void setPurchaseHistoryRestored() {
        saveBoolean(getPreferencesBaseKey() + RESTORE_KEY, true);
    }

    private void savePurchasePayload(String value) {
        saveString(getPreferencesBaseKey() + PURCHASE_PAYLOAD_CACHE_KEY, value);
    }

    private String getPurchasePayload() {
        return loadString(getPreferencesBaseKey() + PURCHASE_PAYLOAD_CACHE_KEY, null);
    }

    private void reportBillingError(int errorCode, Throwable error) {
        Log.e(LOG_TAG, "error with code: " + errorCode, error);
        if (eventHandler != null) {
            eventHandler.onBillingError(errorCode, error);
        }
    }

    /**
     * Returns the most recent purchase made by the user for each SKU, even if that purchase is expired, canceled, or consumed.
     *
     * @param type        product type, accepts either {@value Constants#PRODUCT_TYPE_MANAGED} or
     *                    {@value Constants#PRODUCT_TYPE_SUBSCRIPTION}
     * @param extraParams a Bundle with extra params that would be appended into http request
     *                    query string. Not used at this moment. Reserved for future functionality.
     * @return @NotNull list of billing history records
     * @throws BillingCommunicationException if billing isn't connected or there was an error during request execution
     */
    public List<BillingHistoryRecord> getPurchaseHistory(String type, Bundle extraParams) throws BillingCommunicationException {

        if (!type.equals(Constants.PRODUCT_TYPE_MANAGED) && !type.equals(Constants.PRODUCT_TYPE_SUBSCRIPTION)) {
            throw new RuntimeException("Unsupported type " + type);
        }

        if (billingClient != null) {

            try {
                List<BillingHistoryRecord> result = new ArrayList<>();
                final BillingResult[] purchaseResult = new BillingResult[1];
                final ArrayList<Purchase> purchaseList = new ArrayList<>();

                billingClient.queryPurchasesAsync(type, new PurchasesResponseListener() {
                    @Override
                    public void onQueryPurchasesResponse(@NonNull BillingResult billingResult, @NonNull List<Purchase> list) {
                        purchaseResult[0] = billingResult;
                        purchaseList.addAll(list);
                    }
                });

                if (purchaseResult[0].getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    if (purchaseList.size() > 0) {
                        for (Purchase purchase : purchaseList) {
                            String jsonData = purchase.getOriginalJson();
                            String signature = purchase.getSignature();

                            if (!TextUtils.isEmpty(jsonData) && !TextUtils.isEmpty(signature)) {
                                BillingHistoryRecord record = new BillingHistoryRecord(jsonData, signature);
                                result.add(record);
                            }
                        }
                    }
                }
                return result;
            } catch (JSONException e) {
                throw new BillingCommunicationException(e);
            }

        } else {
            throw new BillingCommunicationException("Billing service isn't connected");
        }
    }
}