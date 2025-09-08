package com.lightningkite.services.database

import kotlinx.serialization.KSerializer
import kotlin.jvm.JvmName

@Suppress("UNCHECKED_CAST")
public fun <Model : HasId<ID>, ID> KSerializer<Model>._id(): SerializableProperty<Model, ID> =
    serializableProperties!!.find { it.name == "_id" }!! as SerializableProperty<Model, ID>

@Suppress("UNCHECKED_CAST")
public fun <Model : HasEmail> KSerializer<Model>.email(): SerializableProperty<Model, String> =
    serializableProperties!!.find { it.name == "email" }!! as SerializableProperty<Model, String>

@Suppress("UNCHECKED_CAST")
public fun <Model : HasPhoneNumber> KSerializer<Model>.phoneNumber(): SerializableProperty<Model, String> =
    serializableProperties!!.find { it.name == "phoneNumber" }!! as SerializableProperty<Model, String>

@JvmName("emailMaybe")
@Suppress("UNCHECKED_CAST")
public fun <Model : HasMaybeEmail> KSerializer<Model>.email(): SerializableProperty<Model, String?> =
    serializableProperties!!.find { it.name == "email" }!! as SerializableProperty<Model, String?>

@JvmName("phoneNumberMaybe")
@Suppress("UNCHECKED_CAST")
public fun <Model : HasMaybePhoneNumber> KSerializer<Model>.phoneNumber(): SerializableProperty<Model, String?> =
    serializableProperties!!.find { it.name == "phoneNumber" }!! as SerializableProperty<Model, String?>

@Suppress("UNCHECKED_CAST")
public fun <Model : HasPassword> KSerializer<Model>.hashedPassword(): SerializableProperty<Model, String> =
    serializableProperties!!.find { it.name == "hashedPassword" }!! as SerializableProperty<Model, String>
