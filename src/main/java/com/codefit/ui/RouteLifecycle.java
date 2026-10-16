package com.codefit.ui;

/** A route can finish saving local work before the shell replaces it or closes. FX-thread calls. */
public interface RouteLifecycle {
    void requestLeave(Runnable ready);

    void dispose();
}
