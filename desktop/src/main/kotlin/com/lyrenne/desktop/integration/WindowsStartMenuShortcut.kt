package com.lyrenne.desktop.integration

import com.lyrenne.desktop.AppPaths
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import timber.log.Timber
import java.io.File

/**
 * Writes the one Start Menu shortcut that lets Windows put Lyrenne's name on its media controls.
 *
 * Windows names a Now Playing source by resolving the session's app id, and its resolver knows
 * about two things: packaged apps, and Start Menu shortcuts carrying a matching
 * `System.AppUserModel.ID`. A portable build has no package, so without the shortcut the flyout
 * shows the track, the artwork and the transport buttons under the heading "Unknown app". Setting
 * the id on the process is necessary but not sufficient on its own; see [WindowsAppIdentity].
 *
 * This is the only file Lyrenne writes outside its own folder, which is why it is opt-in rather
 * than something that happens on first run. Turning the setting off deletes it again, and it is
 * rewritten whenever the executable moves, since a portable app is expected to move and a shortcut
 * pointing at the old path would name nothing.
 *
 * ### Why this is hand-rolled COM
 *
 * The scriptable shortcut object every example reaches for (`WScript.Shell`) cannot set a property
 * on the link, and the app id is a property: it lives in the link's property store, not in any of
 * the fields that object exposes. Reaching the property store means `IShellLinkW` and
 * `IPropertyStore` directly, and neither has a binding in jna-platform, so the vtables are called
 * by slot here. The slots are fixed by the published interface definitions and cannot drift.
 */
internal object WindowsStartMenuShortcut {

    private val CLSID_ShellLink = Guid.CLSID("{00021401-0000-0000-C000-000000000046}")
    private val IID_IShellLinkW = Guid.IID("{000214F9-0000-0000-C000-000000000046}")
    private val IID_IPersistFile = Guid.IID("{0000010B-0000-0000-C000-000000000046}")
    private val IID_IPropertyStore = Guid.IID("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}")

    /** `System.AppUserModel.ID`, as a PROPERTYKEY: the shell format id plus property id 5. */
    private val FMTID_AppUserModel = Guid.GUID("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}")
    private const val PID_AppUserModel_ID = 5

    // Vtable slots. 0 to 2 are IUnknown on every interface.
    private const val QUERY_INTERFACE = 0
    private const val RELEASE = 2
    private const val ISHELLLINK_SET_DESCRIPTION = 7
    private const val ISHELLLINK_SET_WORKING_DIRECTORY = 9
    private const val ISHELLLINK_SET_ICON_LOCATION = 17
    private const val ISHELLLINK_SET_PATH = 20
    private const val IPERSISTFILE_SAVE = 6
    private const val IPROPERTYSTORE_SET_VALUE = 6
    private const val IPROPERTYSTORE_COMMIT = 7

    private const val S_OK = 0
    private const val S_FALSE = 1
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_INPROC_SERVER = 0x1

    /** PROPVARIANT is 24 bytes on x64: the tag, three reserved shorts, then a 16 byte union. */
    private const val PROPVARIANT_SIZE = 24L

    /** Offset of the union inside PROPVARIANT, where the string pointer goes. */
    private const val PROPVARIANT_VALUE_OFFSET = 8

    /** `VT_LPWSTR`: the union holds a pointer to a null terminated wide string. */
    private const val VT_LPWSTR: Short = 31

    /** PROPERTYKEY is the 16 byte format id followed by the 4 byte property id. */
    private const val PROPERTYKEY_SIZE = 20L

    /** `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Lyrenne.lnk` */
    private val shortcutFile: File
        get() = File(
            System.getenv("APPDATA").orEmpty(),
            "Microsoft\\Windows\\Start Menu\\Programs\\Lyrenne.lnk"
        )

    /**
     * Brings the shortcut into line with [enabled]. Safe to call on every startup, and cheap when
     * nothing has changed.
     */
    fun apply(enabled: Boolean) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        val target = shortcutFile
        try {
            if (!enabled) {
                if (target.exists() && target.delete()) {
                    Timber.i("Removed the Start Menu shortcut")
                }
                return
            }
            val exe = File(AppPaths.appDir, "Lyrenne.exe")
            if (!exe.isFile) {
                Timber.w("No Lyrenne.exe beside the app, skipping the Start Menu shortcut")
                return
            }
            if (target.parentFile?.isDirectory != true) {
                Timber.w("No Start Menu Programs folder, skipping the shortcut")
                return
            }
            if (write(target, exe)) {
                Timber.i("Start Menu shortcut points at ${exe.absolutePath}")
            }
        } catch (e: Throwable) {
            Timber.w("Could not update the Start Menu shortcut: ${e.message}")
        }
    }

    /** Invokes vtable [slot] on a raw COM pointer. */
    private fun call(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = obj.getPointer(0)
        val method = Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE))
        return method.invokeInt(arrayOf(obj, *args))
    }

    private fun write(target: File, exe: File): Boolean {
        val hr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED).toInt()
        if (hr != S_OK && hr != S_FALSE) {
            Timber.w("CoInitializeEx failed: 0x${hr.toString(16)}")
            return false
        }
        // S_FALSE means this thread was already initialised by someone else: ours to use, but not
        // ours to tear down.
        val ownsCom = hr == S_OK
        var link: Pointer? = null
        var store: Pointer? = null
        var persist: Pointer? = null
        var propVariant: Memory? = null
        var propertyKey: Memory? = null
        var idString: Pointer? = null
        try {
            val linkRef = PointerByReference()
            val created = Ole32.INSTANCE.CoCreateInstance(
                CLSID_ShellLink, null, CLSCTX_INPROC_SERVER, IID_IShellLinkW, linkRef
            ).toInt()
            if (created != S_OK) {
                Timber.w("Could not create the shell link object: 0x${created.toString(16)}")
                return false
            }
            link = linkRef.value

            call(link, ISHELLLINK_SET_PATH, WString(exe.absolutePath))
            call(link, ISHELLLINK_SET_WORKING_DIRECTORY, WString(exe.parent.orEmpty()))
            call(link, ISHELLLINK_SET_DESCRIPTION, WString("Lyrenne"))
            call(link, ISHELLLINK_SET_ICON_LOCATION, WString(exe.absolutePath), 0)

            val storeRef = PointerByReference()
            if (call(link, QUERY_INTERFACE, IID_IPropertyStore, storeRef) != S_OK) {
                Timber.w("The shell link has no property store, cannot set the app id")
                return false
            }
            store = storeRef.value

            // The obvious helper here, InitPropVariantFromString, is an inline function in the
            // Windows SDK headers rather than an export: propsys.dll ships only the *Vector forms
            // of it, so loading it by name fails at runtime. The struct is three fields and is
            // built directly instead. The string has to come from the COM allocator because
            // SetValue copies out of it and the shell owns the copy.
            val idBytes = (WindowsAppIdentity.APP_USER_MODEL_ID + "\u0000").toByteArray(Charsets.UTF_16LE)
            idString = Ole32.INSTANCE.CoTaskMemAlloc(idBytes.size.toLong())
            if (idString == null) {
                Timber.w("Could not allocate the app id string")
                return false
            }
            idString.write(0, idBytes, 0, idBytes.size)
            propVariant = Memory(PROPVARIANT_SIZE).apply { clear() }
            propVariant.setShort(0, VT_LPWSTR)
            propVariant.setPointer(PROPVARIANT_VALUE_OFFSET.toLong(), idString)

            // GUID.toByteArray() hands back the bytes in the order the GUID is *written*, which is
            // not how one sits in memory: the first three fields are little endian there. Writing
            // the array straight out therefore produces a valid but meaningless property key, the
            // shell stores the app id under it without complaint, and the only symptom is the name
            // never resolving. Lay the fields out by hand instead.
            propertyKey = Memory(PROPERTYKEY_SIZE).apply { clear() }
            propertyKey.setInt(0, FMTID_AppUserModel.Data1)
            propertyKey.setShort(4, FMTID_AppUserModel.Data2)
            propertyKey.setShort(6, FMTID_AppUserModel.Data3)
            propertyKey.write(8, FMTID_AppUserModel.Data4, 0, 8)
            propertyKey.setInt(16, PID_AppUserModel_ID)

            val set = call(store, IPROPERTYSTORE_SET_VALUE, propertyKey, propVariant)
            if (set != S_OK) {
                Timber.w("Could not set the app id on the shortcut: 0x${set.toString(16)}")
                return false
            }
            val committed = call(store, IPROPERTYSTORE_COMMIT)
            if (committed != S_OK) {
                Timber.w("Could not commit the app id: 0x${committed.toString(16)}")
                return false
            }

            val persistRef = PointerByReference()
            if (call(link, QUERY_INTERFACE, IID_IPersistFile, persistRef) != S_OK) {
                Timber.w("The shell link cannot be saved, no IPersistFile")
                return false
            }
            persist = persistRef.value
            val saved = call(persist, IPERSISTFILE_SAVE, WString(target.absolutePath), true)
            if (saved != S_OK) {
                Timber.w("Could not save the shortcut: 0x${saved.toString(16)}")
                return false
            }
            return true
        } finally {
            persist?.let { call(it, RELEASE) }
            store?.let { call(it, RELEASE) }
            link?.let { call(it, RELEASE) }
            propertyKey?.close()
            propVariant?.close()
            idString?.let { Ole32.INSTANCE.CoTaskMemFree(it) }
            if (ownsCom) Ole32.INSTANCE.CoUninitialize()
        }
    }
}
