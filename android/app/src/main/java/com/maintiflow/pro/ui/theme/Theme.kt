package com.maintiflow.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Navy = Color(0xFF0B3D5C)
val Orange = Color(0xFFF57C00)

private val Light = lightColorScheme(
    primary = Navy, onPrimary = Color.White, secondary = Orange, onSecondary = Color.White,
    background = Color(0xFFF4F6F8), surface = Color.White, surfaceVariant = Color(0xFFE6ECF0),
    error = Color(0xFFC62828),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7FB8DD), onPrimary = Color(0xFF00243A), secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3E2000), background = Color(0xFF101418), surface = Color(0xFF1A2026),
    surfaceVariant = Color(0xFF26313A), error = Color(0xFFEF9A9A),
)

// Textes plus grands que le défaut : lecture à distance et utilisation avec des gants légers.
private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun MaintiFlowTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}

/** Couleurs d'état identiques partout (liste, fiche, PDF). */
object StatusStyle {
    val label = mapOf(
        "NOUVEAU" to "Nouveau", "EN_ATTENTE" to "En attente", "ACCEPTE" to "Accepté", "EN_COURS" to "En cours",
        "EN_ATTENTE_PIECE" to "Attente pièce", "REPARE" to "Réparé", "VALIDATION_PRODUCTION" to "Validation prod.",
        "CLOTURE" to "Clôturé", "ANNULE" to "Annulé",
        // Version 2 (serveur MaintiFlow 2 : téléphone maître ou PC).
        "EN_DIAGNOSTIC" to "En diagnostic", "INTERVENTION_MAINTENANCE" to "Intervention Maintenance",
        "INTERVENTION_METHODE" to "Intervention Méthode", "EN_ATTENTE_QUALITE" to "Attente Qualité",
        "RESOLUE" to "Résolue, à clôturer", "REOUVERTE" to "Réouverte (récidive)",
    )
    val color = mapOf(
        "NOUVEAU" to Color(0xFFD32F2F), "EN_ATTENTE" to Color(0xFFE64A19), "ACCEPTE" to Color(0xFF1976D2),
        "EN_COURS" to Color(0xFF0288D1), "EN_ATTENTE_PIECE" to Color(0xFF8E24AA), "REPARE" to Color(0xFF2E7D32),
        "VALIDATION_PRODUCTION" to Color(0xFF00897B), "CLOTURE" to Color(0xFF2E7D32), "ANNULE" to Color(0xFF9E9E9E),
        // Rose : déclaration ; jaune : intervention ; vert : clôturé.
        "REOUVERTE" to Color(0xFFC2185B), "EN_DIAGNOSTIC" to Color(0xFFB28704),
        "INTERVENTION_MAINTENANCE" to Color(0xFFB28704), "INTERVENTION_METHODE" to Color(0xFFB28704),
        "EN_ATTENTE_QUALITE" to Color(0xFFE07B00), "RESOLUE" to Color(0xFF00897B),
    )
    val priorityColor = mapOf(
        "CRITIQUE" to Color(0xFFB71C1C), "HAUTE" to Color(0xFFEF6C00), "NORMALE" to Color(0xFF1565C0),
        "BASSE" to Color(0xFF607D8B),
    )
    val roleLabel = mapOf(
        "CHEF_EQUIPE" to "Chef d'équipe Production", "TECHNICIEN" to "Technicien Maintenance",
        "RESPONSABLE" to "Responsable Maintenance", "ADMIN" to "Administrateur",
        "OPERATEUR" to "Opérateur", "CHEF_LIGNE" to "Chef de ligne", "MAINTENANCIER" to "Maintenancier",
        "METHODE" to "Méthode", "QUALITE" to "Qualité", "LOGISTIQUE" to "Logistique",
    )
    /** Rôles qui prennent en charge et remplissent la page jaune (TECHNICIEN : serveur version 1). */
    val intervenants = setOf("TECHNICIEN", "MAINTENANCIER", "METHODE")
    val chefs = setOf("CHEF_EQUIPE", "CHEF_LIGNE")
    val actionLabel = mapOf(
        "assign" to "Affecter", "accept" to "PRENDRE EN CHARGE", "arrive" to "Arrivé sur machine",
        "start" to "DÉMARRER", "pause" to "Suspendre", "resume" to "Reprendre", "wait_part" to "Attente pièce",
        "repair" to "TERMINER – RÉPARÉ", "sign_maintenance" to "SIGNER LE RAPPORT",
        "validate_maintenance" to "VALIDER (RESPONSABLE)", "reject_maintenance" to "Renvoyer au technicien",
        "confirm_production" to "CONFIRMER LA REPRISE PRODUCTION", "reject_production" to "Problème non résolu",
        "cancel" to "Annuler le bon", "reopen" to "Réouvrir", "flag_recurring" to "Panne récurrente",
        "transfer" to "Transférer Maintenance / Méthode", "part_delivered" to "PIÈCE LIVRÉE",
        "part_unavailable" to "Pièce indisponible", "finish" to "TERMINER L'INTERVENTION",
        "quality_validate" to "VALIDER (QUALITÉ)", "quality_reject" to "Refuser (Qualité)",
        "close" to "CLÔTURER", "reject_resolution" to "Panne non résolue",
    )
    /** Actions principales (gros bouton plein) ; les autres sont secondaires. */
    val primaryActions = setOf("accept", "start", "repair", "sign_maintenance", "validate_maintenance",
        "confirm_production", "resume", "finish", "part_delivered", "quality_validate", "close")
    val eventLabel = mapOf(
        "CREATION" to "Déclaration", "AFFECTATION" to "Affectation", "PRISE_EN_CHARGE" to "Prise en charge",
        "ARRIVEE" to "Arrivée sur machine", "DEMARRAGE" to "Début d'intervention", "PAUSE" to "Suspension",
        "RESUME" to "Reprise", "REPRISE" to "Reprise", "ATTENTE_PIECE" to "Attente de pièce",
        "REPARATION_TERMINEE" to "Réparation terminée", "SIGNATURE" to "Signature", "MODIFICATION" to "Modification",
        "VALIDATION_MAINTENANCE" to "Validation maintenance", "RENVOI_TECHNICIEN" to "Renvoi au technicien",
        "CONFIRMATION_PRODUCTION" to "Reprise production confirmée", "REFUS_PRODUCTION" to "Refus production",
        "ANNULATION" to "Annulation", "REOUVERTURE" to "Réouverture", "PANNE_RECURRENTE" to "Panne récurrente",
        "PIECE_AJOUTEE" to "Pièce ajoutée", "PIECE_RETIREE" to "Pièce retirée", "PHOTO_AJOUTEE" to "Photo ajoutée",
        "PIECE_LIVREE" to "Pièce livrée", "PIECE_INDISPONIBLE" to "Pièce indisponible", "TRANSFERT" to "Transfert",
        "INTERVENTION_TERMINEE" to "Intervention terminée", "VALIDATION_QUALITE" to "Validation Qualité",
        "REFUS_QUALITE" to "Refus Qualité", "CLOTURE" to "Clôture", "REFUS_CLOTURE" to "Clôture refusée",
    )
    val machineStates = listOf("OPERATIONNELLE" to "Opérationnelle", "DEGRADEE" to "Dégradée", "ARRETEE" to "Arrêtée")
    val priorities = listOf("CRITIQUE", "HAUTE", "NORMALE", "BASSE")
}
