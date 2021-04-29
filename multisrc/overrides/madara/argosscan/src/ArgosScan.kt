package eu.kanade.tachiyomi.extension.pt.argosscan

import eu.kanade.tachiyomi.multisrc.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

class ArgosScan : Madara("Argos Scan", "https://argosscan.com", "pt-BR", SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("pt", "BR")))
