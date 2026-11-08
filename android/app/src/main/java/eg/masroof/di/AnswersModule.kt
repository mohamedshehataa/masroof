package eg.masroof.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eg.masroof.data.answers.AnswerPairLookup
import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.answers.AnswerRepository
import eg.masroof.data.answers.CashAccountLabel
import eg.masroof.data.answers.DataStoreAnswerPrefs
import eg.masroof.data.answers.DefaultAnswerRepository
import eg.masroof.data.answers.PairLookup
import eg.masroof.data.answers.ResourceCashAccountLabel
import eg.masroof.data.edit.DefaultDuplicateRepository
import eg.masroof.data.edit.DuplicateRepository

/** "Needs an answer" cards (STORY-208). */
@Module
@InstallIn(SingletonComponent::class)
abstract class AnswersModule {
    @Binds abstract fun prefs(impl: DataStoreAnswerPrefs): AnswerPrefs

    @Binds abstract fun repository(impl: DefaultAnswerRepository): AnswerRepository

    @Binds abstract fun cashLabel(impl: ResourceCashAccountLabel): CashAccountLabel

    @Binds abstract fun pairs(impl: AnswerPairLookup): PairLookup

    /** STORY-173: "Possible duplicate" cards of a manual entry and a capture. */
    @Binds abstract fun duplicates(impl: DefaultDuplicateRepository): DuplicateRepository
}
