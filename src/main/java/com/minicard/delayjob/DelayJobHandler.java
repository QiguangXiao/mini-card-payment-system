package com.minicard.delayjob;


public interface DelayJobHandler {

    DelayJobType jobType();

    void handle(DelayJob job);
}
