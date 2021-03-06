package de.greenrobot.event.util;

/**
 * A generic failure event, which can be used by apps to propagate thrown exceptions. Also used in conjunction with
 * {@link ErrorDialogManager}.
 */
public class ThrowableFailureEvent implements HasExecutionScope {
    protected final Throwable throwable;
    protected final boolean suppressErrorUi;
    private Object executionContext;

    public ThrowableFailureEvent(Throwable throwable) {
        this.throwable = throwable;
        suppressErrorUi = false;
    }

    /**
     * @param suppressErrorUi
     *            true indicates to the receiver that no error UI (e.g. dialog) should now displayed.
     */
    public ThrowableFailureEvent(Throwable throwable, boolean suppressErrorUi) {
        this.throwable = throwable;
        this.suppressErrorUi = suppressErrorUi;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public boolean isSuppressErrorUi() {
        return suppressErrorUi;
    }

    public Object getExecutionScope() {
        return executionContext;
    }

    public void setExecutionScope(Object executionContext) {
        this.executionContext = executionContext;
    }
    
}
