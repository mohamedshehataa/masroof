package eg.masroof.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eg.masroof.data.bills.BillsReminderSource
import eg.masroof.data.bills.DataStoreSubscriptionPrefs
import eg.masroof.data.bills.DefaultInstallmentRepository
import eg.masroof.data.bills.DefaultSubscriptionRepository
import eg.masroof.data.bills.InstallmentRepository
import eg.masroof.data.bills.PaydayProvider
import eg.masroof.data.bills.SubscriptionPrefs
import eg.masroof.data.bills.SubscriptionReminderSource
import eg.masroof.data.bills.SubscriptionRepository
import eg.masroof.work.AndroidReminderNotifier
import eg.masroof.work.PrefsReminderLog
import eg.masroof.work.ReminderLog
import eg.masroof.work.ReminderNotifier
import eg.masroof.work.ReminderSource
import javax.inject.Singleton

/**
 * Bills (STORY-202). Later features that have things falling due add their own `@Binds @IntoSet ReminderSource` in their module;
 * the daily worker collects the whole set and is never edited.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BillsBindingsModule {
    @Binds abstract fun installments(impl: DefaultInstallmentRepository): InstallmentRepository

    @Binds @IntoSet
    abstract fun billsReminders(impl: BillsReminderSource): ReminderSource

    @Binds abstract fun subscriptions(impl: DefaultSubscriptionRepository): SubscriptionRepository

    /** The days-to-payday hook Home and Ask call later (STORY-203). */
    @Binds abstract fun payday(impl: DefaultSubscriptionRepository): PaydayProvider

    @Binds abstract fun subscriptionPrefs(impl: DataStoreSubscriptionPrefs): SubscriptionPrefs

    @Binds @IntoSet
    abstract fun subscriptionReminders(impl: SubscriptionReminderSource): ReminderSource

    @Binds abstract fun reminderNotifier(impl: AndroidReminderNotifier): ReminderNotifier
}

@Module
@InstallIn(SingletonComponent::class)
object BillsProvidersModule {
    @Provides @Singleton
    fun reminderLog(
        @ApplicationContext context: Context,
    ): ReminderLog = PrefsReminderLog(context.getSharedPreferences("masroof_reminders", Context.MODE_PRIVATE))
}
