@file:Suppress("PropertyName")

package com.lightningkite.services.database

public interface HasId<ID : Comparable<ID>> {
    public val _id: ID
}

public interface TypedId<ID : Comparable<ID>, TYPE : TypedId<ID, TYPE>> : Comparable<TYPE> {
    public val raw: ID
    override fun compareTo(other: TYPE): Int = raw.compareTo(other.raw)
}

public interface HasEmail {
    public val email: String
}

public interface HasPhoneNumber {
    public val phoneNumber: String
}

public interface HasMaybeEmail {
    public val email: String?
}

public interface HasMaybePhoneNumber {
    public val phoneNumber: String?
}

public interface HasPassword {
    public val hashedPassword: String
}

