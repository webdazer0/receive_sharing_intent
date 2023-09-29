package com.kasem.receive_sharing_intent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.common.PluginRegistry.NewIntentListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection

private const val MESSAGES_CHANNEL = "receive_sharing_intent/messages"
private const val EVENTS_CHANNEL_MEDIA = "receive_sharing_intent/events-media"
private const val EVENTS_CHANNEL_TEXT = "receive_sharing_intent/events-text"

class ReceiveSharingIntentPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
        EventChannel.StreamHandler, NewIntentListener {

    //private var TAG: String = javaClass.simpleName 
    private var TAG: String = "Webdazer0"

    private var initialMedia: JSONArray? = null
    private var latestMedia: JSONArray? = null

    private var initialText: String? = null
    private var latestText: String? = null

    private var eventSinkMedia: EventChannel.EventSink? = null
    private var eventSinkText: EventChannel.EventSink? = null

    private var binding: ActivityPluginBinding? = null
    private lateinit var applicationContext: Context

    private fun setupCallbackChannels(binaryMessenger: BinaryMessenger) {
        val mChannel = MethodChannel(binaryMessenger, MESSAGES_CHANNEL)
        val eChannelMedia = EventChannel(binaryMessenger, EVENTS_CHANNEL_MEDIA)
        val eChannelText = EventChannel(binaryMessenger, EVENTS_CHANNEL_TEXT)

        mChannel.setMethodCallHandler(this)
        eChannelMedia.setStreamHandler(this)
        eChannelText.setStreamHandler(this)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        setupCallbackChannels(binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        when (arguments) {
            "media" -> eventSinkMedia = events
            "text" -> eventSinkText = events
        }
    }

    override fun onCancel(arguments: Any?) {
        when (arguments) {
            "media" -> eventSinkMedia = null
            "text" -> eventSinkText = null
        }
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = ReceiveSharingIntentPlugin()
            instance.applicationContext = registrar.context()
            instance.setupCallbackChannels(registrar.messenger())
        }
    }


    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getInitialMedia" -> result.success(initialMedia?.toString())
            "getInitialText" -> result.success(initialText)
            "reset" -> {
                initialMedia = null
                latestMedia = null
                initialText = null
                latestText = null
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private  fun logger(value: String) : Unit {
        Log.i(TAG, value)
    }
    private  fun containsExtraStream(intent: Intent) : Boolean {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
//                infoFromUri(uri)
                return uri != null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uriList = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                return uriList != null
            }
            else -> false
        }
    }

    private fun handleIntent(intent: Intent, initial: Boolean) {
        val type = intent.type ?: return // MimeType
        logger("[MIME] =>: $type")
//        infoFromIntent(intent)
        val hasText = intent.getStringExtra(Intent.EXTRA_TEXT) != null
        val hasExtraStream = containsExtraStream(intent)
        val isOnlyActionSend: Boolean = intent.action == Intent.ACTION_SEND
        val isActionSend: Boolean = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
        val isActionView: Boolean = intent.action == Intent.ACTION_VIEW
//        val isVCard = type.contains("vcard") // text/x-vcard
//        val isTypeVideo = type.startsWith("video")
//        val isTypeImage = type.startsWith("image")

        when {
            isActionSend && hasExtraStream -> { // Sharing images or videos
                val value = getMediaUris(intent)
                if (initial) initialMedia = value
                latestMedia = value
                eventSinkMedia?.success(latestMedia?.toString())
            }
            hasText && isOnlyActionSend -> { // Sharing text
                val value = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (initial) initialText = value
                latestText = value
                eventSinkText?.success(latestText)
            }
            isActionView -> { // Opening URL
                val value = intent.dataString
                if (initial) initialText = value
                latestText = value
                eventSinkText?.success(latestText)
            }
        }
    }

    private fun getMediaUris(intent: Intent?): JSONArray? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val path = uri?.let{ FileDirectory.getAbsolutePath(applicationContext, it) }
                logger("$path")

                if (path != null) {
                    val type = getMediaType(path)
                    logger("o=> $type")
                    val thumbnail = getThumbnail(path, type)
                    val duration = getDuration(path, type)
                    val jsonObject = JSONObject()
                            .put("path", path)
                            .put("type", type.ordinal)
                            .put("thumbnail", thumbnail)
                            .put("duration", duration)

                    JSONArray().put(jsonObject)
                } else null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                val value = uris?.mapNotNull { uri ->
                    val path = FileDirectory.getAbsolutePath(applicationContext, uri)
                            ?: return@mapNotNull null
                    val type = getMediaType(path)
                    val thumbnail = getThumbnail(path, type)
                    val duration = getDuration(path, type)
                    return@mapNotNull JSONObject()
                            .put("path", path)
                            .put("type", type.ordinal)
                            .put("thumbnail", thumbnail)
                            .put("duration", duration)
                }?.toList()
                if (value != null) JSONArray(value) else null
            }
            else -> null
        }
    }

    private fun getMediaType(path: String?): MediaType {
        val mimeType = URLConnection.guessContentTypeFromName(path)
        return when {
            mimeType?.startsWith("image") == true -> MediaType.IMAGE
            mimeType?.startsWith("video") == true -> MediaType.VIDEO
            else -> MediaType.FILE
        }
    }

    private fun getThumbnail(path: String, type: MediaType): String? {
        if (type != MediaType.VIDEO) return null // get video thumbnail only

        val videoFile = File(path)
        val targetFile = File(applicationContext.cacheDir, "${videoFile.name}.png")
        val bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                ?: return null
        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return targetFile.path
    }

    private fun getDuration(path: String, type: MediaType): Long? {
        if (type != MediaType.VIDEO) return null // get duration for video only
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.release()
        return duration
    }

    private  fun infoFromIntent(intent: Intent) : Unit {
        logger("[intent] =>: ${intent.toString()}")
        val list = intent.extras?.keySet()?.map { it to (intent.extras?.get(it) ?: "[-]")  }
        logger("[extras] ${list?.size}: ${list?.toString()}")
    }

    private  fun infoFromUri(uri: Uri) : Unit {
        val path = uri?.let{ FileDirectory.getAbsolutePath(applicationContext, it) }
        logger("[SEND_STREAM:uri]: | ${uri != null} ${uri.toString()}")
        logger("[SEND_STREAM:path]: | $path")
    }

    enum class MediaType {
        IMAGE, VIDEO, FILE;
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
        handleIntent(binding.activity.intent, true)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        handleIntent(intent, false)
        return false
    }
}
