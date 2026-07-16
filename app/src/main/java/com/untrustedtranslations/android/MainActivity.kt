package com.untrustedtranslations.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.untrustedtranslations.android.ui.TranslationApp
import com.untrustedtranslations.android.ui.UntrustedTranslationsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UntrustedTranslationsTheme { Surface(Modifier.fillMaxSize()) { TranslationApp() } } }
    }
}
