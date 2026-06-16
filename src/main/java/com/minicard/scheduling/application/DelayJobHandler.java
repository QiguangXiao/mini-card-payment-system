package com.minicard.scheduling.application;

import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobType;

public interface DelayJobHandler {

    DelayJobType jobType();

    void handle(DelayJob job);
}
