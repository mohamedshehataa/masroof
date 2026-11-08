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
import eg.masroof.data.bills.DefaultInstallmentRepository
import eg.masroof.data.bills.InstallmentRepository
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
