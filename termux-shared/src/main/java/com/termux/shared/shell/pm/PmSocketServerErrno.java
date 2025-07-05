package com.termux.shared.shell.pm;

import com.termux.shared.errors.Errno;

public class PmSocketServerErrno extends Errno {

    public static final String TYPE = "PmSocketServer Error";


    /** Errors for {@link PmSocketServer} (100-150) */
    public static final Errno ERRNO_PARSE_PM_COMMAND_FAILED_WITH_EXCEPTION = new Errno(TYPE, 100, "Parse pm command `%1$s` failed.\nException: %2$s");
    public static final Errno ERRNO_RUN_PM_COMMAND_FAILED_WITH_EXCEPTION = new Errno(TYPE, 101, "Run pm command `%1$s` failed.\nException: %2$s");

    PmSocketServerErrno(final String type, final int code, final String message) {
        super(type, code, message);
    }

}
