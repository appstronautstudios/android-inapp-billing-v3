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
package com.anjlab.android.iab.v3.sample2;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anjlab.android.iab.v3.BillingProcessor;
import com.anjlab.android.iab.v3.SkuDetails;
import com.anjlab.android.iab.v3.SuccessFailListener;
import com.anjlab.android.iab.v3.TransactionDetails;

import java.util.ArrayList;

public class MainActivity extends Activity implements BillingProcessor.IBillingHandler {
    // SAMPLE APP CONSTANTS
    private static final String ACTIVITY_NUMBER = "activity_num";
    private static final String LOG_TAG = "iabv3";

    // PRODUCT & SUBSCRIPTION IDS
    private static final String PRODUCT_ID = "com.anjlab.test.iab.s2.p5";
    private static final String SUBSCRIPTION_ID = "com.anjlab.test.iab.subs1";
    private static final String LICENSE_KEY = null; // PUT YOUR MERCHANT KEY HERE;
    // put your Google merchant id here (as stated in public profile of your Payments Merchant Center)
    // if filled library will provide protection against Freedom alike Play Market simulators
    private static final String MERCHANT_ID = null;

    private BillingProcessor bp;
    private boolean readyToPurchase = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView title = (TextView) findViewById(R.id.titleTextView);
        title.setText(String.format(getString(R.string.title), getIntent().getIntExtra(ACTIVITY_NUMBER, 1)));

        if (!BillingProcessor.isIabServiceAvailable(this)) {
            showToast("In-app billing service is unavailable, please upgrade Android Market/Play to version >= 3.9.16");
        }

        bp = new BillingProcessor(this, LICENSE_KEY, MERCHANT_ID, this);
        bp.initialize();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTextViews();
    }

    @Override
    public void onDestroy() {
        if (bp != null)
            bp.release();
        super.onDestroy();
    }

    private void updateTextViews() {
        TextView text = (TextView) findViewById(R.id.productIdTextView);
        text.setText(String.format("%s is%s purchased", PRODUCT_ID, bp.isPurchased(PRODUCT_ID) ? "" : " not"));
        text = (TextView) findViewById(R.id.subscriptionIdTextView);
        text.setText(String.format("%s is%s subscribed", SUBSCRIPTION_ID, bp.isSubscribed(SUBSCRIPTION_ID) ? "" : " not"));
    }

    private void showToast(final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void onClick(View v) {
        if (!readyToPurchase) {
            showToast("Billing not initialized.");
            return;
        }
        switch (v.getId()) {
            case R.id.purchaseButton:
                bp.purchase(this, PRODUCT_ID);
                break;
            case R.id.consumeButton:
                bp.consumePurchase(PRODUCT_ID);
                updateTextViews();
                break;
            case R.id.productDetailsButton:
                bp.getPurchaseListingDetails(PRODUCT_ID, new SuccessFailListener() {
                    @Override
                    public void success(Object object) {
                        ArrayList<SkuDetails> details = (ArrayList<SkuDetails>) object;
                        if (details.size() > 0) {
                            showToast(details.get(0).toString());
                        } else {
                            showToast("Failed to load product details");
                        }
                    }

                    @Override
                    public void fail(Object object) {
                        showToast("Failed to load product details");
                    }
                });
                break;
            case R.id.subscribeButton:
                bp.subscribe(this, SUBSCRIPTION_ID);
                break;
            case R.id.updateSubscriptionsButton:
                if (bp.loadOwnedPurchasesFromGoogle()) {
                    showToast("Subscriptions updated.");
                    updateTextViews();
                }
                break;
            case R.id.subsDetailsButton:
                bp.getSubscriptionListingDetails(SUBSCRIPTION_ID, new SuccessFailListener() {
                    @Override
                    public void success(Object object) {
                        ArrayList<SkuDetails> details = (ArrayList<SkuDetails>) object;
                        if (details.size() > 0) {
                            showToast(details.get(0).toString());
                        } else {
                            showToast("Failed to load subscription details");
                        }
                    }

                    @Override
                    public void fail(Object object) {
                        showToast("Failed to load subscription details");
                    }
                });
                break;
            case R.id.launchMoreButton:
                startActivity(new Intent(this, MainActivity.class).putExtra(ACTIVITY_NUMBER, getIntent().getIntExtra(ACTIVITY_NUMBER, 1) + 1));
                break;
            default:
                break;
        }
    }

    @Override
    public void onProductPurchased(@NonNull String productId, @Nullable TransactionDetails details) {
        showToast("onProductPurchased: " + productId);
        updateTextViews();
    }

    @Override
    public void onPurchaseHistoryRestored() {
        showToast("onPurchaseHistoryRestored");
        for (String sku : bp.listOwnedProducts())
            Log.d(LOG_TAG, "Owned Managed Product: " + sku);
        for (String sku : bp.listOwnedSubscriptions())
            Log.d(LOG_TAG, "Owned Subscription: " + sku);
        updateTextViews();
    }

    @Override
    public void onBillingError(int errorCode, @Nullable Throwable error) {
        showToast("onBillingError: " + Integer.toString(errorCode));
    }

    @Override
    public void onBillingInitialized() {
        showToast("onBillingInitialized");
        readyToPurchase = true;
        updateTextViews();
    }
}
