# Durable and portable JSON is an external contract. Every reflected field has an explicit
# SerializedName; these exact class rules add defense in depth by retaining its no-arg constructor
# and original JVM field name. Class names may still be obfuscated and optimized. Keep this list in
# sync with GsonWireContractTest and scripts/verify_r8_json_contract.py.

-keepclassmembers class com.theoriacodex.data.storage.LegacyImportProof {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
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
-keepclassmembers class com.theoriacodex.data.repository.UiRestoreDataStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacySettingsStoreRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacyProviderHealthSnapshotRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacyFavoriteTagEntryRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacyForYouBlacklistEntryRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacyRecommendationProfileRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacyUiRestoreStoreRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacySearchScrollStateRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LegacyViewerLaunchContextRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.repository.CodexStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.CodexRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.CodexItemRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.QueryStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.QueryRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.SearchTermRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.RecentsStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.RecentPostRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.RecentSearchRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.SettingsStoreFile {
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
-keepclassmembers class com.theoriacodex.data.repository.UiRestoreStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.SearchScrollStateRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.ViewerLaunchContextRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LikesStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.repository.LikedPostRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.theoriacodex.data.android.room.LegacyCodexStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.android.room.LegacyCodexRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.android.room.LegacyCodexItemRecord {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.android.room.LegacyLikesStoreFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.data.android.room.LegacyLikedPostRecord {
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
-keepclassmembers class com.theoriacodex.app.update.UpdateStateSnapshot {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.update.PendingPostInstallChangelog {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.update.ChangelogSection {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.search.TagStoreSnapshot {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.search.TagStoreEntry {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.codex.CodexShareFile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.codex.CodexSharePost {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.codex.CodexSharePostSnapshot {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.codex.CodexShareImageRef {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.codex.CodexShareTaxonomyTerm {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.app.codex.CodexShareCreatorProfile {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.theoriacodex.sources.hitomi.HitomiSourceAdapter$HitomiPageToken {
    <init>();
    @com.google.gson.annotations.SerializedName <fields>;
}
