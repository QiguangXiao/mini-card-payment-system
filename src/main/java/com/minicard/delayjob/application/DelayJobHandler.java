package com.minicard.delayjob.application;

import com.minicard.delayjob.domain.DelayJob;
import com.minicard.delayjob.domain.DelayJobType;

public interface DelayJobHandler {

    DelayJobType jobType();

    void handle(DelayJob job);
}
