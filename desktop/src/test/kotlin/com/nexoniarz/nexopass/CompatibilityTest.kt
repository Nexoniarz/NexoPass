package com.nexoniarz.nexopass

import com.nexoniarz.nexopass.core.Export
import com.nexoniarz.nexopass.core.NexoPass
import com.nexoniarz.nexopass.core.PgpVaultCodec
import com.nexoniarz.nexopass.core.Rec
import com.nexoniarz.nexopass.core.Vault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Expected values come from the nexopass script. If any of these change,
 * every user's passwords change: never "fix" a test by editing the numbers.
 */
class CompatibilityTest {
    private val words = NexoPass.loadWords(javaClass.getResourceAsStream("/eff_large_wordlist.txt")!!.readBytes())
    private val master = NexoPass.normalizeMaster("  Test  Master\tZmyslony ").toByteArray()

    @Test fun masterNormalization() {
        assertEquals("test master zmyslony", master.decodeToString())
        assertEquals("ąść żółw synapse", NexoPass.normalizeMaster("ĄŚĆ  Żółw SYNAPSE"))
    }

    @Test fun checkWords() = assertEquals("swiftly remarry", NexoPass.checkWords(master, words))

    @Test fun passwords() {
        assertEquals("Gnat-Timing-Green-Myth-Guileless3", NexoPass.password(master, words, "discord", "", 1, "words"))
        assertEquals("Unsaved-Outsell-Appear-Perplexed-Cringe5", NexoPass.password(master, words, "discord", "", 2, "words"))
        assertEquals("t=sYCgU2W5e!Y*37tKyj", NexoPass.password(master, words, "discord", "", 3, "chars20"))
        assertEquals("UQSFpi=5t=Tt&@CA", NexoPass.password(master, words, "allegro", "", 1, "chars16"))
        assertEquals("Reference-Map-Garnet-Pantry-Headset8", NexoPass.password(master, words, "allegro", "drugie", 1, "words"))
        assertEquals("Survivor-Ooze-Afflicted-Activity-Passivism-Obstinate-Childless9", NexoPass.password(master, words, "steam", "", 1, "words7"))
        assertEquals(
            "Crowbar-Scolding-Richness-Onscreen-Recall-Empathy-Kleenex-Malt-Entourage-Edging2",
            NexoPass.password(master, words, "discord", "", 2, "words10"),
        )
    }

    @Test fun otherAccount() {
        val work = "work master phrase here".toByteArray()
        assertEquals("legible lively", NexoPass.checkWords(work, words))
        assertEquals("Capillary-Hush-Angelfish-Moonshine-Aloha8", NexoPass.password(work, words, "github", "", 1, "words"))
    }

    @Test fun siteNames() {
        mapOf(
            "https://www.Discord.com/login" to "discord",
            "allegro.pl" to "allegro",
            "onet.com.pl" to "onet",
            "store.steampowered.com" to "steampowered",
            "user@mail.example.com:443/x" to "example",
        ).forEach { (raw, want) -> assertEquals(want, NexoPass.normalizeSite(raw), raw) }
        assertNull(NexoPass.normalizeSite("a..b"))
        assertNull(NexoPass.normalizeSite("ąę.pl"))
    }

    @Test fun vaultAndExportRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val key = NexoPass.vaultKey(master)
        val v = Vault(dir.resolve("vault.gpg"), key, PgpVaultCodec)
        v.add("discord", "", 1, "words")
        val again = Vault(dir.resolve("vault.gpg"), key, PgpVaultCodec)
        assertEquals(true, again.load())
        assertEquals(listOf("discord"), again.records.map(Rec::site))
        assertEquals(false, Vault(dir.resolve("vault.gpg"), NexoPass.vaultKey("wrong one here".toByteArray()), PgpVaultCodec).load())

        val pass = NexoPass.exportPassphrase(master)
        assertEquals("hello", Export.decrypt(pass, Export.encrypt(pass, "hello".toByteArray()))?.decodeToString())
        dir.deleteRecursively()
    }
}
