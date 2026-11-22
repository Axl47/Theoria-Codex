// Binary-compatible stand-in for the four Injekt classes extension binaries call (uy.kohesive.injekt 1.16).
package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektScope

/** The service locator extensions ask for the host's `Application`, `Json` and `NetworkHelper`. */
val Injekt: InjektScope = InjektScope()
