package com.termux.shared.shell.pm;

import android.Manifest;
import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.pm.Pm;
import com.termux.shared.R;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.ILocalSocketManager;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalServerSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.shell.ArgumentTokenizer;
import com.termux.shared.shell.command.ExecutionCommand;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A AF_UNIX/SOCK_STREAM local server managed with {@link LocalSocketManager} whose
 * {@link LocalServerSocket} receives android activity manager (pm) commands from {@link LocalClientSocket}
 * and runs them with termux-pm-library. It would normally only allow processes belonging to the
 * server app's user and root user to connect to it.
 *
 * The client must send the pm command as a string without the initial "pm" arg on its output stream
 * and then wait for the result on its input stream. The result of the execution or error is sent
 * back in the format `exit_code\0stdout\0stderr\0` where `\0` represents a null character.
 * Check termux/termux-pm-socket for implementation of a native c client.
 *
 * Usage:
 * 1. Optionally extend {@link PmSocketServerClient}, the implementation for
 *    {@link ILocalSocketManager} that will receive call backs from the server including
 *    when client connects via {@link ILocalSocketManager#onClientAccepted(LocalSocketManager, LocalClientSocket)}.
 * 2. Create a {@link PmSocketServerRunConfig} instance which extends from {@link LocalSocketRunConfig}
 *    with the run config of the pm server. It would  be better to use a filesystem socket instead
 *    of abstract namespace socket for security reasons.
 * 3. Call {@link #start(Context, LocalSocketRunConfig)} to start the server and store the {@link LocalSocketManager}
 *    instance returned.
 * 4. Stop server if needed with a call to {@link LocalSocketManager#stop()} on the
 *    {@link LocalSocketManager} instance returned by start call.
 *
 */
public class PmSocketServer {

    public static final String LOG_TAG = "PmSocketServer";

    /**
     * Create the {@link PmSocketServer} {@link LocalServerSocket} and start listening for new {@link LocalClientSocket}.
     *
     * @param context The {@link Context} for {@link LocalSocketManager}.
     * @param localSocketRunConfig The {@link LocalSocketRunConfig} for {@link LocalSocketManager}.
     */
    public static synchronized LocalSocketManager start(@NonNull Context context,
                                                        @NonNull LocalSocketRunConfig localSocketRunConfig) {
        LocalSocketManager localSocketManager = new LocalSocketManager(context, localSocketRunConfig);
        Error error = localSocketManager.start();
        if (error != null) {
            localSocketManager.onError(error);
            return null;
        }

        return localSocketManager;
    }

    public static void processPmClient(@NonNull LocalSocketManager localSocketManager,
                                       @NonNull LocalClientSocket clientSocket) {
        Error error;

        // Read pmCommandString client sent and close input stream
        StringBuilder data = new StringBuilder();
        error = clientSocket.readDataOnInputStream(data, true);
        if (error != null) {
            sendResultToClient(localSocketManager, clientSocket, 1, null, error.toString());
            return;
        }

        String pmCommandString = data.toString();

        Logger.logVerbose(LOG_TAG, "pm command received from peer " + clientSocket.getPeerCred().getMinimalString() +
            "\npm command: `" + pmCommandString + "`");

        // Parse pm command string and convert it to a list of arguments
        List<String> pmCommandList = new ArrayList<>();
        error = parsePmCommand(pmCommandString, pmCommandList);
        if (error != null) {
            sendResultToClient(localSocketManager, clientSocket, 1, null, error.toString());
            return;
        }

        String[] pmCommandArray = pmCommandList.toArray(new String[0]);

        Logger.logDebug(LOG_TAG, "pm command received from peer " + clientSocket.getPeerCred().getMinimalString() +
            "\n" + ExecutionCommand.getArgumentsLogString("pm command", pmCommandArray));

        PmSocketServerRunConfig pmSocketServerRunConfig = (PmSocketServerRunConfig) localSocketManager.getLocalSocketRunConfig();

        // Run pm command and send its result to the client
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        error = runPmCommand(localSocketManager.getContext(), pmCommandArray, stdout, stderr,
            pmSocketServerRunConfig.shouldCheckDisplayOverAppsPermission());
        if (error != null) {
            sendResultToClient(localSocketManager, clientSocket, 1, stdout.toString(),
                !stderr.toString().isEmpty() ? stderr + "\n\n" + error : error.toString());
        }

        sendResultToClient(localSocketManager, clientSocket, 0, stdout.toString(), stderr.toString());
    }

    /**
     * Send result to {@link LocalClientSocket} that requested the pm command to be run.
     *
     * @param localSocketManager The {@link LocalSocketManager} instance for the local socket.
     * @param clientSocket The {@link LocalClientSocket} to which the result is to be sent.
     * @param exitCode The exit code value to send.
     * @param stdout The stdout value to send.
     * @param stderr The stderr value to send.
     */
    public static void sendResultToClient(@NonNull LocalSocketManager localSocketManager,
                                          @NonNull LocalClientSocket clientSocket,
                                          int exitCode,
                                          @Nullable String stdout, @Nullable String stderr) {
        StringBuilder result = new StringBuilder();
        result.append(sanitizeExitCode(clientSocket, exitCode));
        result.append('\0');
        result.append(stdout != null ? stdout : "");
        result.append('\0');
        result.append(stderr != null ? stderr : "");

        // Send result to client and close output stream
        Error error = clientSocket.sendDataToOutputStream(result.toString(), true);
        if (error != null) {
            localSocketManager.onError(clientSocket, error);
        }
    }

    /**
     * Sanitize exitCode to between 0-255, otherwise it may be considered invalid.
     * Out of bound exit codes would return with exit code `44` `Channel number out of range` in shell.
     *
     * @param clientSocket The {@link LocalClientSocket} to which the exit code will be sent.
     * @param exitCode The current exit code.
     * @return Returns the sanitized exit code.
     */
    public static int sanitizeExitCode(@NonNull LocalClientSocket clientSocket, int exitCode) {
        if (exitCode < 0 || exitCode > 255) {
            Logger.logWarn(LOG_TAG, "Ignoring invalid peer "  + clientSocket.getPeerCred().getMinimalString() + " result value \"" + exitCode + "\" and force setting it to \"" + 1 + "\"");
            exitCode = 1;
        }

        return exitCode;
    }


    /**
     * Parse pmCommandString into a list of arguments like normally done on shells like bourne shell.
     * Arguments are split on whitespaces unless quoted with single or double quotes.
     * Double quotes and backslashes can be escaped with backslashes in arguments surrounded.
     * Double quotes and backslashes can be escaped with backslashes in arguments surrounded with
     * double quotes.
     *
     * @param pmCommandString The pm command {@link String}.
     * @param pmCommandList The {@link List<String>} to set list of arguments in.
     * @return Returns the {@code error} if parsing pm command failed, otherwise {@code null}.
     */
    public static Error parsePmCommand(String pmCommandString, List<String> pmCommandList) {

        if (pmCommandString == null || pmCommandString.isEmpty()) {
            return null;
        }

        try {
            pmCommandList.addAll(ArgumentTokenizer.tokenize(pmCommandString));
        } catch (Exception e) {
            return PmSocketServerErrno.ERRNO_PARSE_PM_COMMAND_FAILED_WITH_EXCEPTION.getError(e, pmCommandString, e.getMessage());
        }

        return null;
    }

    /**
     * Call termux-pm-library to run the pm command.
     *
     * @param context The {@link Context} to run am command with.
     * @param pmCommandArray The pm command array.
     * @param stdout The {@link StringBuilder} to set stdout in that is returned by the pm command.
     * @param stderr The {@link StringBuilder} to set stderr in that is returned by the pm command.
     * @param checkDisplayOverAppsPermission Check if {@link Manifest.permission#SYSTEM_ALERT_WINDOW}
     *                                       has been granted if running on Android `>= 10` and
     *                                       starting activity or service.
     * @return Returns the {@code error} if pm command failed, otherwise {@code null}.
     */
    public static Error runPmCommand(@NonNull Context context,
                                     String[] pmCommandArray,
                                     @NonNull StringBuilder stdout, @NonNull StringBuilder stderr,
                                     boolean checkDisplayOverAppsPermission) {
        try (ByteArrayOutputStream stdoutByteStream = new ByteArrayOutputStream();
             PrintStream stdoutPrintStream = new PrintStream(stdoutByteStream);
             ByteArrayOutputStream stderrByteStream = new ByteArrayOutputStream();
             PrintStream stderrPrintStream = new PrintStream(stderrByteStream)) {

            new Pm(stdoutPrintStream, stderrPrintStream, (Application) context.getApplicationContext()).run(pmCommandArray);

            // Set stdout to value set by am command in stdoutPrintStream
            stdoutPrintStream.flush();
            stdout.append(stdoutByteStream.toString(StandardCharsets.UTF_8.name()));

            // Set stderr to value set by am command in stderrPrintStream
            stderrPrintStream.flush();
            stderr.append(stderrByteStream.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            return PmSocketServerErrno.ERRNO_RUN_PM_COMMAND_FAILED_WITH_EXCEPTION.getError(e, Arrays.toString(amCommandArray), e.getMessage());
        }

        return null;
    }





    /** Implementation for {@link ILocalSocketManager} for {@link PmSocketServer}. */
    public abstract static class PmSocketServerClient extends LocalSocketManagerClientBase {

        @Override
        public void onClientAccepted(@NonNull LocalSocketManager localSocketManager,
                                     @NonNull LocalClientSocket clientSocket) {
            PmSocketServer.processPmClient(localSocketManager, clientSocket);
            super.onClientAccepted(localSocketManager, clientSocket);
        }

    }

}
