package com.lightningkite.services.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class KotlinBytesFormatTest {
    val javaData = KotlinBytesFormat(EmptySerializersModule())
    val stringArray = StringArrayFormat(EmptySerializersModule())
    val json = Json { serializersModule = EmptySerializersModule() }

    @Serializable
    data class HubReport(
        val id: String,
        val secret1: Long,
        val secret2: Long,
        val battery: Float,
        val voltage: Float,
        val latitude: Float,
        val longitude: Float,
        val firmware: Int,
        val seen: List<Tag>,
    ) {
        companion object

        @Serializable
        data class Tag(
            val rssi: Byte,
            val major: Short,
            val minor: Short,
        ) {
            companion object
        }
    }

    @Test
    fun test() {
        val example = HubReport(
            id = "127981723819123",
            secret1 = 2L,
            secret2 = 3L,
            battery = 1f,
            voltage = 2f,
            latitude = 2f,
            longitude = 2f,
            firmware = 1,
            seen = listOf(
                HubReport.Tag(rssi = -110, major = 1, minor = 1),
                HubReport.Tag(rssi = -110, major = 1, minor = 2),
                HubReport.Tag(rssi = -110, major = 1, minor = 3),
            )
        )
        val hex = javaData.encodeToHexString(example)
        println(hex)
        println(javaData.decodeFromHexString<HubReport>(hex))
    }


    @Test
    fun permissionsFromHcp() {
        val sample = CompletePermissions(
            organizations = setOf(
                FinalPermissions(
                    directOwner = Uuid.parse("55555555-5555-5555-5555-555555555555"),
                    owners = setOf(Uuid.parse("55555555-5555-5555-5555-555555555555")),
                    member = Uuid.parse("55555555-5555-5555-5555-555555555555"),
                    manageBalance = true,
                    minimalMemberRead = true,
                    notifications = true,
                    sds = true,
                    subscriptions = true,
                    associates = Access.Edit,
                    applicants = Access.Edit,
                    billing = Access.Edit,
                    content = Access.Edit,
                    documents = Access.Edit,
                    exclusionMatches = Access.Edit,
                    organizations = Access.Edit,
                    policies = Access.Edit,
                    policyAnswers = Access.Edit,
                    roles = Access.Edit,
                    tags = Access.Edit,
                    tasks = Access.Edit,
                    taskSchedules = Access.Edit,
                    memberDocuments = Access.Edit,
                    members = Access.Edit,
                )
            ),
            services = FinalServicePermissions()
        )
        assertEquals(
            sample, javaData.decodeFromByteArray(
                CompletePermissions.serializer(),
                javaData.encodeToByteArray(CompletePermissions.serializer(), sample)
            )
        )
        val serializer = CompletePermissions.serializer()
        assertEquals(
            sample,
            stringArray.decodeFromStringList(
                serializer,
                stringArray.encodeToStringList(serializer, sample).also { println(it) })
        )
        assertEquals(
            sample,
            stringArray.decodeFromString(
                serializer,
                stringArray.encodeToString(serializer, sample).also { println(it) })
        )
    }

    @Test
    fun permissionsFromHcp2() {
        val sample2 = json.decodeFromString(
            CompletePermissions.serializer(), """
            {
            	"organizations": [
            		{
            			"directOwner": "85ee13e4-71ac-4474-bc21-1bcd392f889a",
            			"owners": [
            				"85ee13e4-71ac-4474-bc21-1bcd392f889a"
            			],
            			"member": "3a8a8f0e-845d-4783-b5bb-d56c68fa8f2d",
            			"manageBalance": false,
            			"minimalMemberRead": false,
            			"notifications": false,
            			"sds": false,
            			"subscriptions": false,
            			"associates": "None",
            			"applicants": "None",
            			"billing": "None",
            			"content": "None",
            			"documents": "None",
            			"exclusionMatches": "None",
            			"organizations": "None",
            			"policies": "None",
            			"policyAnswers": "None",
            			"roles": "None",
            			"tags": "None",
            			"tasks": "None",
            			"taskSchedules": "None",
            			"memberDocuments": "None",
            			"members": "None"
            		}
            	],
            	"services": {
            		"forms": "None",
            		"content": "None",
            		"policies": "None",
            		"policyQuestions": "None",
            		"products": "None",
            		"roles": "None",
            		"sds": "None",
            		"tags": "None"
            	}
            }
        """.trimIndent()
        )
        assertEquals(
            sample2, javaData.decodeFromByteArray(
                CompletePermissions.serializer(),
                javaData.encodeToByteArray(CompletePermissions.serializer(), sample2)
            )
        )
        val serializer = CompletePermissions.serializer()
        assertEquals(
            sample2,
            stringArray.decodeFromStringList(
                serializer,
                stringArray.encodeToStringList(serializer, sample2)
            )
        )
        assertEquals(
            sample2,
            stringArray.decodeFromString(
                serializer,
                stringArray.encodeToString(serializer, sample2)
            )
        )
    }

    @Serializable
    data class StringContainer(
        val string1: String = "",
        val string2: String = "",
    )

    // The charactor – in string1 is not a -. It's different. This caused Serialization errors that has now been resolved.
    @Test
    fun testUnicodeStrings() {
        val value = StringContainer(string1 = "INF–03–026 C", string2 = "")

        val output = javaData.encodeToHexString(StringContainer.serializer(), value)
        javaData.decodeFromHexString<StringContainer>(output)
    }


    interface Permissions<Whole, Crud> {
        val manageBalance: Whole
        val minimalMemberRead: Whole
        val notifications: Whole
        val sds: Whole
        val subscriptions: Whole

        // Access Based Permissions
        val associates: Crud
        val applicants: Crud
        val billing: Crud
        val content: Crud
        val documents: Crud
        val exclusionMatches: Crud
        val memberDocuments: Crud
        val members: Crud
        val organizations: Crud
        val policies: Crud
        val policyAnswers: Crud
        val roles: Crud
        val tags: Crud
        val tasks: Crud
        val taskSchedules: Crud
    }

    interface ServicePermissions<Whole, Crud> {
        val forms: Crud
        val content: Crud
        val policies: Crud
        val policyQuestions: Crud
        val products: Crud
        val roles: Crud
        val sds: Crud
        val tags: Crud
    }

    @Serializable
    data class FinalServicePermissions(
        override val forms: Access = Access.None,
        override val content: Access = Access.None,
        override val policies: Access = Access.None,
        override val policyQuestions: Access = Access.None,
        override val products: Access = Access.None,
        override val roles: Access = Access.None,
        override val sds: Access = Access.None,
        override val tags: Access = Access.None,
    ) : ServicePermissions<Boolean, Access>

    @Serializable
    enum class Access {
        None,
        View,
        Edit,
        Full,
        Delegate,
        Administrate,
    }

    @Serializable
    data class FinalPermissions(
        val directOwner: Uuid,
        val owners: Set<Uuid>,
        val member: Uuid,
        override val manageBalance: Boolean,
        override val minimalMemberRead: Boolean,
        override val notifications: Boolean,
        override val sds: Boolean,
        override val subscriptions: Boolean,
        override val associates: Access,
        override val applicants: Access,
        override val billing: Access,
        override val content: Access,
        override val documents: Access,
        override val exclusionMatches: Access,
        override val organizations: Access,
        override val policies: Access,
        override val policyAnswers: Access,
        override val roles: Access,
        override val tags: Access,
        override val tasks: Access,
        override val taskSchedules: Access,
        override val memberDocuments: Access,
        override val members: Access,
    ) : Permissions<Boolean, Access>

    @Serializable
    data class CompletePermissions(
        val organizations: Set<FinalPermissions>,
        val services: FinalServicePermissions,
    ) {
    }
}