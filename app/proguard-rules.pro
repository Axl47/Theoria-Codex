# Durable and portable JSON is an external contract. Every reflected field has an explicit
# SerializedName; these exact class rules add defense in depth by retaining its no-arg constructor
# and original JVM field name. Class names may still be obfuscated and optimized. Keep this list in
# sync with GsonWireContractTest and scripts/verify_r8_json_contract.py.

-keepclassmembers class com.theoriacodex.data.storage.PostStorageRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.storage.PostTaxonomyTermStorageRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.storage.CreatorProfileStorageRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.storage.ImageRefStorageRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.repository.SettingsDataStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.StatisticsStoreRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.DailyActivity {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.CodexStatistics {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.CodexPostStatistics {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.StatisticsTagCountRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.StatisticsTypedTagCountRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.SettingsStoreRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.ProviderHealthSnapshotRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.FavoriteTagEntryRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.ForYouBlacklistEntryRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.RecommendationProfileRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.storage.QueryStorageRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.storage.SearchTermStorageRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.app.sourceauth.CredentialEnvelopeRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.sourceauth.CredentialPayloadRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.sourceauth.CustomSiteCredentialRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.statistics.StatsShareFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.statistics.StatsShareCodex {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.statistics.StatsSharePost {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.statistics.StatsShareOverall {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.storage.VideoVariantRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.repository.FollowedCreatorRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.repository.SiteProfileRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
