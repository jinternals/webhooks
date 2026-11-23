package com.jinternals.webhooks.consumer.web.error;

import java.io.Serial;

/** Raised when an incoming event type is not accepted and the policy is REJECT (see SPEC.md §5, §12). */
public class UnknownTypeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String type;
    private final String path;

    public UnknownTypeException(String type, String path, String reason) {
        super("Unknown webhook type '" + type + "' on " + path + " (" + reason + ")");
        this.type = type;
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public String getPath() {
        return path;
    }
}
