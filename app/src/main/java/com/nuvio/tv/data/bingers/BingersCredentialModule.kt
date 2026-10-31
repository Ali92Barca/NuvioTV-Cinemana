package com.nuvio.tv.data.bingers

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class BingersCredentialModule {
    @Binds @IntoSet
    abstract fun credentialStore(service: BingersService): ProfileScopedCredentialStore
}
