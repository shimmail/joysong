package com.joysong.app.di

import android.content.Context
import com.joysong.app.BuildConfig
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.AuthInterceptor
import com.joysong.app.data.remote.TokenAuthenticator
import com.joysong.app.data.repository.AiRepositoryImpl
import com.joysong.app.data.repository.AuthRepositoryImpl
import com.joysong.app.data.repository.CommentRepositoryImpl
import com.joysong.app.data.repository.CouponRepositoryImpl
import com.joysong.app.data.repository.DiaryRepositoryImpl
import com.joysong.app.data.repository.DiscoverRepositoryImpl
import com.joysong.app.data.repository.FavoriteRepositoryImpl
import com.joysong.app.data.repository.FileRepositoryImpl
import com.joysong.app.data.repository.HomeRepositoryImpl
import com.joysong.app.data.repository.LikeRepositoryImpl
import com.joysong.app.data.repository.NotificationRepositoryImpl
import com.joysong.app.data.repository.OrderRepositoryImpl
import com.joysong.app.data.repository.PaymentRepositoryImpl
import com.joysong.app.data.repository.ReportRepositoryImpl
import com.joysong.app.data.repository.TranslationRepositoryImpl
import com.joysong.app.data.repository.UserRepositoryImpl
import com.joysong.app.domain.repository.AiRepository
import com.joysong.app.domain.repository.AuthRepository
import com.joysong.app.domain.repository.CommentRepository
import com.joysong.app.domain.repository.CouponRepository
import com.joysong.app.domain.repository.DiaryRepository
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.FavoriteRepository
import com.joysong.app.domain.repository.FileRepository
import com.joysong.app.domain.repository.HomeRepository
import com.joysong.app.domain.repository.LikeRepository
import com.joysong.app.domain.repository.NotificationRepository
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.domain.repository.PaymentRepository
import com.joysong.app.domain.repository.ReportRepository
import com.joysong.app.domain.repository.TranslationRepository
import com.joysong.app.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindHomeRepository(impl: HomeRepositoryImpl): HomeRepository

    @Binds
    @Singleton
    abstract fun bindDiscoverRepository(impl: DiscoverRepositoryImpl): DiscoverRepository

    @Binds
    @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): OrderRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindDiaryRepository(impl: DiaryRepositoryImpl): DiaryRepository

    @Binds
    @Singleton
    abstract fun bindLikeRepository(impl: LikeRepositoryImpl): LikeRepository

    @Binds
    @Singleton
    abstract fun bindCommentRepository(impl: CommentRepositoryImpl): CommentRepository

    @Binds
    @Singleton
    abstract fun bindFavoriteRepository(impl: FavoriteRepositoryImpl): FavoriteRepository

    @Binds
    @Singleton
    abstract fun bindPaymentRepository(impl: PaymentRepositoryImpl): PaymentRepository

    @Binds
    @Singleton
    abstract fun bindAiRepository(impl: AiRepositoryImpl): AiRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository

    @Binds
    @Singleton
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository

    @Binds
    @Singleton
    abstract fun bindReportRepository(impl: ReportRepositoryImpl): ReportRepository

    @Binds
    @Singleton
    abstract fun bindCouponRepository(impl: CouponRepositoryImpl): CouponRepository

    @Binds
    @Singleton
    abstract fun bindTranslationRepository(impl: TranslationRepositoryImpl): TranslationRepository

    companion object {

        @Provides
        @Singleton
        fun provideTokenManager(@ApplicationContext context: Context): TokenManager {
            return TokenManager(context)
        }

        @Provides
        @Singleton
        fun provideOkHttpClient(tokenManager: TokenManager): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenManager))
                .authenticator(TokenAuthenticator(tokenManager))
                .addInterceptor(HttpLoggingInterceptor().apply {
                    redactHeader("Authorization")
                    // 请求体包含密码、验证码与刷新令牌，不在日志中输出。
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                })
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        @Provides
        @Singleton
        fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
            return Retrofit.Builder()
                .baseUrl(BuildConfig.SERVER_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        @Provides
        @Singleton
        fun provideApiService(retrofit: Retrofit): ApiService {
            return retrofit.create(ApiService::class.java)
        }
    }
}
