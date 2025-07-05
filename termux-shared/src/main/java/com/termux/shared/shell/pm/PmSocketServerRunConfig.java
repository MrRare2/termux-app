package com.termux.shared.shell.pm;

import android.Manifest;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.net.socket.local.ILocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;

import java.io.Serializable;

public class PmSocketServerRunConfig extends LocalSocketRunConfig implements Serializable {
    private Boolean mCheckDisplayOverAppsPermission;
    public static final boolean DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION = true;

    public PmSocketServerRunConfig(@NonNull String title, @NonNull String path, @NonNull ILocalSocketManager localSocketManagerClient) {
        super(title, path, localSocketManagerClient);
    }

    public boolean shouldCheckDisplayOverAppsPermission() {
        return mCheckDisplayOverAppsPermission != null ? mCheckDisplayOverAppsPermission : DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION;
    }

    public void setCheckDisplayOverAppsPermission(Boolean checkDisplayOverAppsPermission) {
        mCheckDisplayOverAppsPermission = checkDisplayOverAppsPermission;
    }

    @NonNull
    public static String getRunConfigLogString(final PmSocketServerRunConfig config) {
        if (config == null) return "null";
        return config.getLogString();
    }

    @NonNull
    public String getLogString() {
        StringBuilder logString = new StringBuilder();
        logString.append(super.getLogString()).append("\n\n\n");

        logString.append("Pm Command:");
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"));

        return logString.toString();
    }

    public static String getRunConfigMarkdownString(final PmSocketServerRunConfig config) {
        if (config == null) return "null";
        return config.getMarkdownString();
    }

    @NonNull
    public String getMarkdownString() {
        StringBuilder markdownString = new StringBuilder();
        markdownString.append(super.getMarkdownString()).append("\n\n\n");

        markdownString.append("## ").append("Pm Command");
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"));

        return markdownString.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return getLogString();
    }

}
