package eg.masroof.di

import android.content.ContentResolver
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eg.masroof.ingest.backfill.AndroidBackfillNotifier
import eg.masroof.ingest.backfill.BackfillNotifier
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ingest.backfill.ContentResolverSmsInbox
import eg.masroof.ingest.backfill.SmsInbox
import javax.inject.Singleton

/** "Read past SMS" (STORY-186): the inbox reader and the one summary notification. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackfillBindingsModule {
    @Binds abstract fun inbox(impl: ContentResolverSmsInbox): SmsInbox

    @Binds abstract fun notifier(impl: AndroidBackfillNotifier): BackfillNotifier
}

/** The content resolver and the run state / 30-day memory of a backfill. */
@Module
@InstallIn(SingletonComponent::class)
object BackfillProvidesModule {
    @Provides @Singleton
    fun contentResolver(
        @ApplicationContext context: Context,
    ): ContentResolver = context.contentResolver

    @Provides @Singleton
    fun backfillStore(
        @ApplicationContext context: Context,
    ): BackfillStore = BackfillStore(context.getSharedPreferences(BackfillStore.PREFS_NAME, Context.MODE_PRIVATE))
}
