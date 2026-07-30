package com.example.srmremoter;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

public final class SRMRemoterApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColorsOptions options = new DynamicColorsOptions.Builder()
                .setThemeOverlay(R.style.ThemeOverlay_SRMRemoter_DynamicColors)
                .build();
        DynamicColors.applyToActivitiesIfAvailable(this, options);
    }
}
