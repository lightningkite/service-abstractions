package com.lightningkite.services.notifications.fcm

import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

class TfTest {
    @Test fun testExistingFirebaseProject() {
        assertPlannableAws<NotificationService.Settings>(vpc = false) {
            it.existingFirebaseProject(
                credentialsPath = "/path/to/firebase-credentials.json"
            )
        }
    }
    
    // Skipping this test as it requires the Google provider which is not fully set up in the test environment
    // @Test fun testFirebaseProject() {
    //     assertPlannableAws<NotificationService.Settings>() {
    //         it.firebaseProject(
    //             projectId = "my-firebase-project",
    //             credentialsPath = "/path/to/firebase-credentials.json"
    //         )
    //     }
    // }
}