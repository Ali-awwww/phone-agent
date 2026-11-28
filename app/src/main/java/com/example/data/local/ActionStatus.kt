package com.example.data.local

enum class ActionStatus(val labelAr: String) {
    SUCCESS("نجح"),
    PENDING_APPROVAL("بانتظار الموافقة"),
    UNVERIFIED("تعذّر التحقق"),
    CANCELLED("أُلغي"),
    FAILED("فشل"),
    UNDONE("تم التراجع");

    companion object {
        fun fromName(name: String?): ActionStatus {
            return entries.firstOrNull { it.name == name } ?: SUCCESS
        }
    }
}
