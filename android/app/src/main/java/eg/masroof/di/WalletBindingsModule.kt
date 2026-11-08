package eg.masroof.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import eg.masroof.data.wallet.ComingUpSource
import eg.masroof.data.wallet.InstallmentsComingUpSource
import eg.masroof.data.wallet.RoomWalletSource
import eg.masroof.data.wallet.SubscriptionsComingUpSource
import eg.masroof.data.wallet.WalletSource

/** Wallet "Coming up" (STORY-206): later features add their own `@Binds @IntoSet ComingUpSource`; Wallet is never edited. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WalletBindingsModule {
    @Multibinds abstract fun comingUpSources(): Set<ComingUpSource>

    @Binds abstract fun walletSource(impl: RoomWalletSource): WalletSource

    @Binds @IntoSet
    abstract fun installments(impl: InstallmentsComingUpSource): ComingUpSource

    @Binds @IntoSet
    abstract fun subscriptions(impl: SubscriptionsComingUpSource): ComingUpSource
}
