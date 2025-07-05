package com.termux.shared.termux.shell.pm;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalServerSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.shell.pm.PmSocketServerRunConfig;
import com.termux.shared.shell.pm.PmSocketServer;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.command.environment.TermuxAppShellEnvironment;

/**
 * A wrapper for {@link PmSocketServer} for termux-app usage.
 *
 * The static {@link #termuxPmSocketServer} variable stores the {@link LocalSocketManager} for the
 * {@link PmSocketServer}.
 *
 * The {@link TermuxPmSocketServerClient} extends the {@link PmSocketServer.PmSocketServerClient}
 * class to also show plugin error notifications for errors and disallowed client connections in
 * addition to logging the messages to logcat, which are only logged by {@link LocalSocketManagerClientBase}
 * if log level is debug or higher for privacy issues.
 *
 * It uses a filesystem socket server with the socket file at
 * {@link TermuxConstants.TERMUX_APP#TERMUX_PM_SOCKET_FILE_PATH}. It would normally only allow
 * processes belonging to the termux user and root user to connect to it. If commands are sent by the
 * root user, then the am commands executed will be run as the termux user and its permissions,
 * capabilities and selinux context instead of root.
 *
 * The `$PREFIX/bin/termux-pm` client connects to the server via `$PREFIX/bin/termux-pm-socket` tO
 * run the pm commands. It provides similar functionality to "$PREFIX/bin/pm"
 * (and "/system/bin/lm"), but should be faster since it does not require starting a dalvik vm for
 * every command as done by "pm" via termux/TermuxPm.
 *
 * The server is started by termux-app Application class but is not started if
 * {@link TermuxPropertyConstants#KEY_RUN_TERMUX_PM_SOCKET_SERVER} is `false` which can be done by
 * adding the prop with value "false" to the "~/.termux/termux.properties" file. Changes
 * require termux-app to be force stopped and restarted.
 *
 * The current state of the server can be checked with the
 * {@link TermuxAppShellEnvironment#ENV_TERMUX_APP__PM_SOCKET_SERVER_ENABLED} env variable, which is exported
 * for all shell sessions and tasks.
 *
 */
public class TermuxPmSocketServer {

    public static final String LOG_TAG = "TermuxPmSocketServer";

    public static final String TITLE = "TermuxPm";

    /** The static instance for the {@link TermuxPmSocketServer} {@link LocalSocketManager}. */
    private static LocalSocketManager termuxPmSocketServer;

    /** Whether {@link TermuxPmSocketServer} is enabled and running or not. */
    @Keep
    protected static Boolean TERMUX_APP_PM_SOCKET_SERVER_ENABLED;

    /**
     * Setup the {@link PmSocketServer} {@link LocalServerSocket} and start listening for
     * new {@link LocalClientSocket} if enabled.
     *
     * @param context The {@link Context} for {@link LocalSocketManager}.
     */
    public static void setupTermuxPmSocketServer(@NonNull Context context) {
        // Start termux-am-socket server if enabled by user
        boolean enabled = false;
        if (TermuxAppSharedProperties.getProperties().shouldRunTermuxPmSocketServer()) {
            Logger.logDebug(LOG_TAG, "Starting " + TITLE + " socket server since its enabled");
            start(context);
            if (termuxPmSocketServer != null && termuxPmSocketServer.isRunning()) {
                enabled = true;
                Logger.logDebug(LOG_TAG, TITLE + " socket server successfully started");
            }
        } else {
            Logger.logDebug(LOG_TAG, "Not starting " + TITLE + " socket server since its not enabled");
        }

        // Once termux-app has started, the server state must not be changed since the variable is
        // exported in shell sessions and tasks and if state is changed, then env of older shells will
        // retain invalid value. User should force stop the app to update state after changing prop.
        TERMUX_APP_PM_SOCKET_SERVER_ENABLED = enabled;
        TermuxAppShellEnvironment.updateTermuxAppPMSocketServerEnabled(context);
    }

    /**
     * Create the {@link PmSocketServer} {@link LocalServerSocket} and start listening for new {@link LocalClientSocket}.
     */
    public static synchronized void start(@NonNull Context context) {
        stop();

        PmSocketServerRunConfig amSocketServerRunConfig = new PmSocketServerRunConfig(TITLE,
            TermuxConstants.TERMUX_APP.TERMUX_PM_SOCKET_FILE_PATH, new TermuxPmSocketServerClient());

        termuxPmSocketServer = PmSocketServer.start(context, amSocketServerRunConfig);
    }

    /**
     * Stop the {@link PmSocketServer} {@link LocalServerSocket} and stop listening for new {@link LocalClientSocket}.
     */
    public static synchronized void stop() {
        if (termuxPmSocketServer != null) {
            Error error = termuxPmSocketServer.stop();
            if (error != null) {
                termuxPmSocketServer.onError(error);
            }
            termuxPmSocketServer = null;
        }
    }
    
    /**
     * Update the state of the {@link PmSocketServer} {@link LocalServerSocket} depending on current
     * value of {@link TermuxPropertyConstants#KEY_RUN_TERMUX_PM_SOCKET_SERVER}.
     */
    public static synchronized void updateState(@NonNull Context context) {
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties.shouldRunTermuxPmSocketServer()) {
            if (termuxPmSocketServer == null) {
                Logger.logDebug(LOG_TAG, "updateState: Starting " + TITLE + " socket server");
                start(context);
            }
        } else {
            if (termuxPmSocketServer != null) {
                Logger.logDebug(LOG_TAG, "updateState: Disabling " + TITLE + " socket server");
                stop();
            }
        }
    }
    
    /**
     * Get {@link #termuxPmSocketServer}.
     */
    public static synchronized LocalSocketManager getTermuxPmSocketServer() {
        return termuxPmSocketServer;
    }

    /**
     * Show an error notification on the {@link TermuxConstants#TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID}
     * {@link TermuxConstants#TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME} with a call
     * to {@link TermuxPluginUtils#sendPluginCommandErrorNotification(Context, String, CharSequence, String, String)}.
     *
     * @param context The {@link Context} to send the notification with.
     * @param error The {@link Error} generated.
     * @param localSocketRunConfig The {@link LocalSocketRunConfig} for {@link LocalSocketManager}.
     * @param clientSocket The optional {@link LocalClientSocket} for which the error was generated.
     */
    public static synchronized void showErrorNotification(@NonNull Context context, @NonNull Error error,
                                                          @NonNull LocalSocketRunConfig localSocketRunConfig,
                                                          @Nullable LocalClientSocket clientSocket) {
        TermuxPluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
            localSocketRunConfig.getTitle() + " Socket Server Error", error.getMinimalErrorString(),
            LocalSocketManager.getErrorMarkdownString(error, localSocketRunConfig, clientSocket));
    }



    public static Boolean getTermuxAppAMSocketServerEnabled(@NonNull Context currentPackageContext) {
        boolean isTermuxApp = TermuxConstants.TERMUX_PACKAGE_NAME.equals(currentPackageContext.getPackageName());
        if (isTermuxApp) {
            return TERMUX_APP_PM_SOCKET_SERVER_ENABLED;
        } else {
            // Currently, unsupported since plugin app processes don't know that value is set in termux
            // app process TermuxPmSocketServer class. A binder API or a way to check if server is actually
            // running needs to be used. Long checks would also not be possible on main application thread
            return null;
        }

    }





    /** Enhanced implementation for {@link PmSocketServer.PmSocketServerClient} for {@link TermuxPmSocketServer}. */
    public static class TermuxPmSocketServerClient extends PmSocketServer.PmSocketServerClient {

        public static final String LOG_TAG = "TermuxPmSocketServerClient";

        @Nullable
        @Override
        public Thread.UncaughtExceptionHandler getLocalSocketManagerClientThreadUEH(
            @NonNull LocalSocketManager localSocketManager) {
            // Use termux crash handler for socket listener thread just like used for main app process thread.
            return TermuxCrashUtils.getCrashHandler(localSocketManager.getContext());
        }

        @Override
        public void onError(@NonNull LocalSocketManager localSocketManager,
                            @Nullable LocalClientSocket clientSocket, @NonNull Error error) {
            // Don't show notification if server is not running since errors may be triggered
            // when server is stopped and server and client sockets are closed.
            if (localSocketManager.isRunning()) {
                TermuxPmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                    localSocketManager.getLocalSocketRunConfig(), clientSocket);
            }

            // But log the exception
            super.onError(localSocketManager, clientSocket, error);
        }

        @Override
        public void onDisallowedClientConnected(@NonNull LocalSocketManager localSocketManager,
                                                @NonNull LocalClientSocket clientSocket, @NonNull Error error) {
            // Always show notification and log error regardless of if server is running or not
            TermuxPmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                localSocketManager.getLocalSocketRunConfig(), clientSocket);
            super.onDisallowedClientConnected(localSocketManager, clientSocket, error);
        }



        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

    }

}
