package eg.masroof.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eg.masroof.ui.onboarding.OnboardingStore
import javax.inject.Singleton

/** The first-run guide's progress (STORY-187): one store for the guide, the Wallet card and Settings. */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingModule {
    @Provides @Singleton
    fun onboardingStore(
        @ApplicationContext context: Context,
    ): OnboardingStore = OnboardingStore(context.getSharedPreferences("masroof_onboarding", Context.MODE_PRIVATE))
}
