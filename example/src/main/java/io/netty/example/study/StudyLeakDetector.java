package io.netty.example.study;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.ResourceLeakTracker;

/**
 *     private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
 *             .newResourceLeakDetector(HashedWheelTimer.class, 1);
 *
 */
public class StudyLeakDetector {
    public static void main(String[] args) {
        StudyLeakDetector studyLeakDetector = new StudyLeakDetector();
        ResourceLeakDetectorFactory leakDetectorFactory = ResourceLeakDetectorFactory.instance();
        ResourceLeakDetector<StudyLeakDetector> leakDetector = leakDetectorFactory.newResourceLeakDetector(StudyLeakDetector.class, 1);

        ResourceLeakTracker<StudyLeakDetector> leak = leakDetector.track(studyLeakDetector);

        // 2022/10/22 liang fix 当在
        leak.close(studyLeakDetector);
    }
}
