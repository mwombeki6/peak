package com.mwombeki.peak.shared.idempotency

//it guarantees that the network retries dont create accidentally duplicate payments or boookings

object IdempotencyContext{
    private val currentkey = ThreadLocal<String>()

    fun setKey(key: String){
        currentkey.set(key)
    }

    fun getKey(): String{
        return currentkey.get()
    }

    fun clear(){
        currentkey.remove()
    }
}