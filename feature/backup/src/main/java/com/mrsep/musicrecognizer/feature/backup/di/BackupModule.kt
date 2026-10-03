package com.mrsep.musicrecognizer.feature.backup.di

import com.mrsep.musicrecognizer.feature.backup.AppBackupManager
import com.mrsep.musicrecognizer.feature.backup.AutoBackupScheduler
import com.mrsep.musicrecognizer.feature.backup.CsvExporter
import com.mrsep.musicrecognizer.feature.backup.CsvExporterImpl
import com.mrsep.musicrecognizer.feature.backup.data.AppBackupManagerImpl
import com.mrsep.musicrecognizer.feature.backup.scheduler.AutoBackupSchedulerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
internal interface BackupModule {

    @Binds
    fun bindAppBackupManager(implementation: AppBackupManagerImpl): AppBackupManager

    @Binds
    fun bindCsvExporter(impl: CsvExporterImpl): CsvExporter

    @Binds
    @Singleton
    fun bindAutoBackupScheduler(impl: AutoBackupSchedulerImpl): AutoBackupScheduler
}
