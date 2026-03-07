package com.example.koperasikitagodangulu

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

fun FirebaseAuth.currentUserFlow(): Flow<FirebaseUser?> = callbackFlow {
    val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }

    addAuthStateListener(authStateListener)

    // Send initial value
    trySend(currentUser)

    awaitClose {
        removeAuthStateListener(authStateListener)
    }
}