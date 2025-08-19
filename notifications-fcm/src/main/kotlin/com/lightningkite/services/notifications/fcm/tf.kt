package com.lightningkite.services.notifications.fcm

import com.lightningkite.services.Untested
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

/**
 * Sets up a Firebase project for FCM notifications.
 * 
 * @param projectId The Firebase project ID
 * @param credentialsPath Path to the Firebase credentials file
 * @return A TerraformServiceResult with the configuration for the Firebase project
 */
@Untested
public fun TerraformNeed<NotificationService.Settings>.firebaseProject(
    projectId: String,
    credentialsPath: String
): TerraformServiceResult<NotificationService.Settings> = TerraformServiceResult(
    need = this,
    setting = $$"fcm://$${credentialsPath}",
    requireProviders = setOf(TerraformProviderImport.google),
    content = terraformJsonObject {
        // These resources would require the Google provider to be properly configured
        "resource.google_firebase_project.$name" {
            "project" - projectId
        }
        
        "resource.google_firebase_project_location.$name" {
            "project" - projectId
            "location_id" - "us-central"
        }
        
        "resource.google_firebase_web_app.$name" {
            "project" - projectId
            "display_name" - "${cloudInfo.projectPrefix}-${name}-web"
            "depends_on" - listOf("google_firebase_project_location.$name")
        }
        
        "resource.google_firebase_android_app.$name" {
            "project" - projectId
            "display_name" - "${cloudInfo.projectPrefix}-${name}-android"
            "package_name" - "com.${cloudInfo.projectPrefix}.${name}"
            "depends_on" - listOf("google_firebase_project_location.$name")
        }
        
        "resource.google_firebase_ios_app.$name" {
            "project" - projectId
            "display_name" - "${cloudInfo.projectPrefix}-${name}-ios"
            "bundle_id" - "com.${cloudInfo.projectPrefix}.${name}"
            "depends_on" - listOf("google_firebase_project_location.$name")
        }
    }
)

/**
 * Uses an existing Firebase project for FCM notifications.
 * 
 * @param credentialsPath Path to the Firebase credentials file
 * @return A TerraformServiceResult with the configuration for the Firebase project
 */
@Untested
public fun TerraformNeed<NotificationService.Settings>.existingFirebaseProject(
    credentialsPath: String
): TerraformServiceResult<NotificationService.Settings> = TerraformServiceResult(
    need = this,
    setting = $$"fcm://$${credentialsPath}",
    requireProviders = setOf(),
    content = terraformJsonObject {}
)