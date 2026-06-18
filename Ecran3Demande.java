// ============================================================
// Ecran3Demande.java
// Mini serveur web complet :
//  - Ecran 1 : Login par email
//  - Ecran 2 : Dashboard filtre par utilisateur
//  - Ecran 3 : Detail d'une demande + POST commentaire
// Avec systeme de session securise par cookie.
// ============================================================
import java.io.FileInputStream;
import java.util.Properties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Ecran3Demande {

    // ==================================================
    // CONFIGURATION
    // ==================================================
    // Lecture de la configuration depuis le fichier config.properties
    // Le fichier n'est PAS dans le depot GitHub pour des raisons de securite
    private static final Properties CONFIG = chargerConfig();
    private static final String TOKEN = CONFIG.getProperty("asana.token");
    private static final String PROJECT_ID = CONFIG.getProperty("asana.project.id");
    private static final int PORT = Integer.parseInt(CONFIG.getProperty("serveur.port", "8080"));

    private static Properties chargerConfig() {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            p.load(fis);
            System.out.println("Configuration chargee depuis config.properties");
        } catch (Exception e) {
            System.err.println("ERREUR : impossible de lire config.properties !");
            System.err.println("Verifiez que le fichier existe dans le dossier courant");
            System.err.println("et qu'il contient les cles asana.token et asana.project.id.");
            System.err.println("Detail : " + e.getMessage());
            System.exit(1);
        }
        return p;
    }

    // ==================================================
    // NOMS DES CHAMPS PERSONNALISES ASANA
    // ==================================================
    private static final String CHAMP_TYPE_DEMANDEUR = "Type de demandeur";
    private static final String CHAMP_NB_PARTICIPANTS = "Nombre de participants";
    private static final String CHAMP_ESPACES = "Espaces/Locaux";
    private static final String CHAMP_FORMATION_SPORTIVE = "Formation Sportive";
    private static final String CHAMP_NETTOYAGE = "Nettoyage";
    private static final String CHAMP_MATERIEL = "Matériel";
    private static final String CHAMP_SERVICE_AV = "Service audio-visuel";
    private static final String CHAMP_SERVICE_RESTAURATION = "Service de Restauration";
    private static final String CHAMP_RESTAURATION = "Restauration";
    private static final String CHAMP_MATERIELS_LISTE = "Matériels";
    private static final String CHAMP_MATERIEL_AV_LISTE = "Matériel Audio-visuel";
    private static final String CHAMP_FX_ACCORD = "Fx(accord de principe)";

    // ==================================================
    // SESSIONS EN MEMOIRE (sessionId -> email)
    // En production, ce serait une vraie BDD (Redis, MySQL...)
    // ==================================================
    private static final Map<String, String> SESSIONS = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    // ==================================================
    // POINT D'ENTREE
    // ==================================================
    public static void main(String[] args) throws Exception {
        HttpServer serveur = HttpServer.create(new InetSocketAddress(PORT), 0);

        // === Endpoint racine : redirige vers login ou dashboard selon session ===
        serveur.createContext("/", echange -> {
            if (echange.getRequestURI().getPath().equals("/favicon.ico")) {
                echange.sendResponseHeaders(404, -1);
                return;
            }
            if (!echange.getRequestURI().getPath().equals("/")) {
                echange.sendResponseHeaders(404, -1);
                return;
            }
            String emailConnecte = recupererEmailSession(echange);
            if (emailConnecte == null) {
                redirigerVers(echange, "/login");
            } else {
                redirigerVers(echange, "/dashboard");
            }
        });

        // === Endpoint LOGIN ===
        serveur.createContext("/login", echange -> {
            if (echange.getRequestMethod().equals("POST")) {
                traiterLogin(echange);
            } else {
                afficherPageLogin(echange, null);
            }
        });

        // === Endpoint LOGOUT ===
        serveur.createContext("/logout", echange -> {
            String sessionId = lireCookieSession(echange);
            if (sessionId != null) {
                SESSIONS.remove(sessionId);
            }
            // Supprime le cookie cote navigateur
            echange.getResponseHeaders().set("Set-Cookie", "sessionId=; HttpOnly; Path=/; Max-Age=0");
            redirigerVers(echange, "/login");
        });

        // === Endpoint DASHBOARD (ecran 2) ===
        serveur.createContext("/dashboard", echange -> {
            String emailConnecte = recupererEmailSession(echange);
            if (emailConnecte == null) {
                redirigerVers(echange, "/login");
                return;
            }
            afficherDashboard(echange, emailConnecte);
        });

        // === Endpoint DETAIL (ecran 3) ===
        serveur.createContext("/detail", echange -> {
            String emailConnecte = recupererEmailSession(echange);
            if (emailConnecte == null) {
                redirigerVers(echange, "/login");
                return;
            }
            String ticket = extraireTicketDepuisUrl(echange);
            if (ticket.isEmpty()) {
                envoyerHtml(echange, construirePageErreur(
                    "Aucun ticket specifie",
                    "Aucune demande n'est specifiee dans l'URL. <a href='/dashboard'>Retour au dashboard</a>"
                ));
                return;
            }
            afficherDetail(echange, ticket, emailConnecte);
        });

        // === Endpoint POST commentaire ===
        serveur.createContext("/commentaire", echange -> {
            String emailConnecte = recupererEmailSession(echange);
            if (emailConnecte == null) {
                redirigerVers(echange, "/login");
                return;
            }
            if (!echange.getRequestMethod().equals("POST")) {
                echange.sendResponseHeaders(405, -1);
                return;
            }

            byte[] corpsBrut = echange.getRequestBody().readAllBytes();
            String corps = new String(corpsBrut, "UTF-8");
            String texteCommentaire = extraireValeurFormulaire(corps, "texte");
            String ticketCible = extraireValeurFormulaire(corps, "ticket");

            if (ticketCible.isEmpty()) {
                redirigerVers(echange, "/dashboard");
                return;
            }

            // SECURITE : verifier que la tache appartient bien a l'utilisateur connecte
            try {
                String json = recupererTacheAsana(ticketCible);
                String emailTache = extraireEmailDesNotes(extraireChamp(extraireContenuDataAplati(json), "notes"));
                if (!emailConnecte.equalsIgnoreCase(emailTache)) {
                    System.out.println("ALERTE SECURITE : " + emailConnecte
                        + " a tente de commenter la tache " + ticketCible
                        + " qui appartient a " + emailTache);
                    envoyerHtml(echange, construirePageErreur(
                        "Acces refuse",
                        "Vous n'avez pas l'autorisation de commenter cette demande."
                    ));
                    return;
                }

                posterCommentaire(ticketCible, texteCommentaire);
                System.out.println("Commentaire envoye par " + emailConnecte + " sur tache " + ticketCible);
            } catch (Exception e) {
                e.printStackTrace();
            }

            redirigerVers(echange, "/detail?ticket=" + ticketCible);
        });

        serveur.setExecutor(null);
        serveur.start();
        System.out.println("====================================");
        System.out.println(" Serveur demarre sur http://localhost:" + PORT);
        System.out.println("====================================");
    }

    // ==================================================
    // GESTION DES SESSIONS
    // ==================================================
    private static String recupererEmailSession(HttpExchange echange) {
        String sessionId = lireCookieSession(echange);
        if (sessionId == null) return null;
        return SESSIONS.get(sessionId);
    }

    private static String lireCookieSession(HttpExchange echange) {
        List<String> cookies = echange.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String entete : cookies) {
            for (String cookie : entete.split(";")) {
                String[] kv = cookie.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("sessionId")) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static String genererSessionId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        // Hex string de 48 caracteres : tres difficile a deviner
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================================================
    // ECRAN 1 : LOGIN
    // ==================================================
    private static void afficherPageLogin(HttpExchange echange, String messageErreur) throws java.io.IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='fr'><head>");
        html.append("<meta charset='UTF-8'><title>Connexion</title>");
        html.append("<style>").append(getCss()).append("</style>");
        html.append("</head><body>");
        html.append("<div class='page-login'>");
        html.append("<div class='login-card'>");
        html.append("<h1 class='login-titre'>Demandes de Moyens</h1>");
        html.append("<div class='login-sous'>École Polytechnique</div>");
        if (messageErreur != null) {
            html.append("<div class='erreur-login'>").append(messageErreur).append("</div>");
        }
        html.append("<form method='POST' action='/login' class='form-login'>");
        html.append("<label for='email'>Adresse e-mail</label>");
        html.append("<input type='email' id='email' name='email' required placeholder='exemple@polytechnique.edu' autofocus>");
        html.append("<button type='submit'>Se connecter</button>");
        html.append("</form>");
        html.append("<div class='login-separateur'>OU</div>");
        html.append("<div class='login-ticket-info'>Numéro de ticket (à venir)</div>");
        html.append("</div></div></body></html>");
        envoyerHtml(echange, html.toString());
    }

    private static void traiterLogin(HttpExchange echange) throws java.io.IOException {
        byte[] corpsBrut = echange.getRequestBody().readAllBytes();
        String corps = new String(corpsBrut, "UTF-8");
        String email = extraireValeurFormulaire(corps, "email").trim().toLowerCase();

        if (email.isEmpty()) {
            afficherPageLogin(echange, "Veuillez saisir une adresse e-mail.");
            return;
        }

        // Creation de la session
        String sessionId = genererSessionId();
        SESSIONS.put(sessionId, email);

        // Envoi du cookie HttpOnly (non accessible au JS = protection XSS)
        echange.getResponseHeaders().set("Set-Cookie",
            "sessionId=" + sessionId + "; HttpOnly; Path=/; Max-Age=86400");

        System.out.println("Connexion : " + email + " (session : " + sessionId.substring(0, 8) + "...)");

        redirigerVers(echange, "/dashboard");
    }

    // ==================================================
    // ECRAN 2 : DASHBOARD
    // ==================================================
    private static void afficherDashboard(HttpExchange echange, String emailConnecte) throws java.io.IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='fr'><head>");
        html.append("<meta charset='UTF-8'><title>Mes demandes</title>");
        html.append("<style>").append(getCss()).append("</style>");
        html.append("</head><body><div class='page'>");

        // En-tete avec email et deconnexion
        html.append("<div class='topbar'>");
        html.append("<div class='topbar-user'>Connecte en tant que <strong>").append(emailConnecte).append("</strong></div>");
        html.append("<a href='/logout' class='btn-logout'>Se deconnecter</a>");
        html.append("</div>");

        html.append("<h1 class='page-titre'>Mes demandes</h1>");

        try {
            List<DemandeListe> mesDemandes = recupererMesDemandes(emailConnecte);

            if (mesDemandes.isEmpty()) {
                html.append("<div class='message-vide'>");
                html.append("<p>Vous n'avez aucune demande pour l'instant.</p>");
                html.append("<p class='message-vide-sub'>Vos demandes apparaîtront ici une fois enregistrées dans Asana.</p>");
                html.append("</div>");
            } else {
                // Classement par section
                Map<String, List<DemandeListe>> parSection = new HashMap<>();
                for (DemandeListe d : mesDemandes) {
                    String sec = d.section.isEmpty() ? "Autres" : d.section;
                    parSection.computeIfAbsent(sec, k -> new ArrayList<>()).add(d);
                }

                html.append("<div class='compteur'>").append(mesDemandes.size())
                    .append(" demande").append(mesDemandes.size() > 1 ? "s" : "").append(" au total</div>");

                for (Map.Entry<String, List<DemandeListe>> entry : parSection.entrySet()) {
                    html.append("<h2 class='section-titre'>").append(entry.getKey())
                        .append(" <span class='section-count'>").append(entry.getValue().size()).append("</span></h2>");
                    html.append("<div class='cartes-grille'>");
                    for (DemandeListe d : entry.getValue()) {
                        html.append("<a href='/detail?ticket=").append(d.id).append("' class='carte'>");
                        html.append("<div class='carte-ticket'>Ticket #").append(d.id).append("</div>");
                        html.append("<div class='carte-nom'>").append(d.nom).append("</div>");
                        if (!d.dateEcheance.isEmpty()) {
                            html.append("<div class='carte-date'>Echeance : ").append(d.dateEcheance).append("</div>");
                        }
                        html.append("<div class='carte-cta'>Voir le detail →</div>");
                        html.append("</a>");
                    }
                    html.append("</div>");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            html.append("<div class='erreur'>Erreur lors du chargement des demandes : ").append(e.getMessage()).append("</div>");
        }

        html.append("</div></body></html>");
        envoyerHtml(echange, html.toString());
    }

    // Recupere TOUTES les taches du projet, parse les notes pour extraire l'email,
    // et ne garde que celles qui appartiennent a l'email connecte.
    private static List<DemandeListe> recupererMesDemandes(String emailConnecte) throws Exception {
        List<DemandeListe> resultat = new ArrayList<>();

        // 1. Lister toutes les taches du projet (juste les IDs)
        String url = "https://app.asana.com/api/1.0/projects/" + PROJECT_ID + "/tasks?opt_fields=gid";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur Asana projets/tasks : " + response.body());
        }

        // Extraire les GIDs des taches
        List<String> gids = new ArrayList<>();
        Pattern p = Pattern.compile("\"gid\"\\s*:\\s*\"(\\d+)\"");
        Matcher m = p.matcher(response.body());
        while (m.find()) {
            gids.add(m.group(1));
        }

        System.out.println("Projet contient " + gids.size() + " taches. Filtrage pour " + emailConnecte + "...");

        // 2. Pour chaque tache, recuperer les notes + statut + nom + echeance
        for (String gid : gids) {
            try {
                String tacheJson = recupererTacheAsana(gid);
                String dataAplati = extraireContenuDataAplati(tacheJson);
                String notes = extraireChamp(dataAplati, "notes");
                String emailDemandeur = extraireEmailDesNotes(notes);

                if (!emailConnecte.equalsIgnoreCase(emailDemandeur)) {
                    continue; // pas la bonne personne, on saute
                }

                DemandeListe d = new DemandeListe();
                d.id = gid;
                d.nom = extraireChamp(dataAplati, "name");
                d.dateEcheance = extraireChamp(dataAplati, "due_on");

                // Section
                String contenuMemberships = extraireContenuTableau(tacheJson, "memberships");
                List<String> objetsMemberships = decouperObjetsJson(contenuMemberships);
                d.section = "";
                if (!objetsMemberships.isEmpty()) {
                    String premier = objetsMemberships.get(0);
                    int idxSection = premier.indexOf("\"section\"");
                    if (idxSection >= 0) {
                        int debutSec = premier.indexOf("{", idxSection);
                        int finSec = trouverFermetureBloc(premier, debutSec);
                        if (debutSec >= 0 && finSec >= 0) {
                            d.section = extraireChamp(premier.substring(debutSec, finSec + 1), "name");
                        }
                    }
                }
                resultat.add(d);
            } catch (Exception e) {
                System.out.println("Erreur sur tache " + gid + " : " + e.getMessage());
            }
        }

        System.out.println(resultat.size() + " demande(s) trouvee(s) pour " + emailConnecte);
        return resultat;
    }

    // Cherche dans les notes la ligne sous "Adresse e-mail:" et renvoie l'email trouve.
    // Gere les notes echappees (\n litteral dans le JSON) et les vrais retours a la ligne.
    private static String extraireEmailDesNotes(String notes) {
        if (notes == null || notes.isEmpty()) return "";
        // Remplace \\n par vrai retour ligne
        String texte = notes.replace("\\n", "\n").replace("\\r", "");
        String[] lignes = texte.split("\n");
        for (int i = 0; i < lignes.length; i++) {
            String l = lignes[i].trim().toLowerCase();
            if (l.startsWith("adresse e-mail") || l.equals("email:") || l.equals("e-mail:")) {
                // Email sur la ligne suivante
                if (i + 1 < lignes.length) {
                    return lignes[i + 1].trim().toLowerCase();
                }
            }
        }
        return "";
    }

    // ==================================================
    // ECRAN 3 : DETAIL D'UNE DEMANDE
    // ==================================================
    private static void afficherDetail(HttpExchange echange, String ticket, String emailConnecte) throws java.io.IOException {
        String html;
        try {
            String json = recupererTacheAsana(ticket);

            // SECURITE : verifier que cette tache appartient bien au connecte
            String notes = extraireChamp(extraireContenuDataAplati(json), "notes");
            String emailTache = extraireEmailDesNotes(notes);
            if (!emailConnecte.equalsIgnoreCase(emailTache)) {
                System.out.println("ALERTE SECURITE : " + emailConnecte
                    + " a tente d'acceder a la tache " + ticket
                    + " qui appartient a " + emailTache);
                html = construirePageErreur(
                    "Acces refuse",
                    "Vous n'avez pas l'autorisation de consulter cette demande. <a href='/dashboard'>Retour au dashboard</a>"
                );
                envoyerHtml(echange, html);
                return;
            }

            Demande demande = parserDemande(json, ticket);
            html = construirePage(demande, null, emailConnecte);
        } catch (Exception e) {
            e.printStackTrace();
            html = construirePageErreur("Erreur", e.getMessage());
        }
        envoyerHtml(echange, html);
    }

    // ==================================================
    // APPELS API ASANA
    // ==================================================
    private static String recupererTacheAsana(String taskId) throws Exception {
        String url = "https://app.asana.com/api/1.0/tasks/" + taskId
                + "?opt_fields=name,notes,due_on,memberships.section.name,"
                + "custom_fields.name,custom_fields.display_value,"
                + "custom_fields.enum_value.color,"
                + "subtasks.name,subtasks.completed";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur Asana (" + response.statusCode() + ") : " + response.body());
        }
        return response.body();
    }

    private static void posterCommentaire(String taskId, String texte) throws Exception {
        String url = "https://app.asana.com/api/1.0/tasks/" + taskId + "/stories";
        String texteJson = texte
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
        String corpsJson = "{\"data\":{\"text\":\"" + texteJson + "\"}}";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpsJson))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new RuntimeException("Erreur POST Asana : " + response.body());
        }
    }

    // ==================================================
    // PARSING (inchange + email helpers)
    // ==================================================
    private static Demande parserDemande(String json, String taskId) {
        Demande d = new Demande();
        d.id = taskId;
        String dataAplati = extraireContenuDataAplati(json);
        d.nom = extraireChamp(dataAplati, "name");
        d.notes = extraireChamp(dataAplati, "notes");
        d.dateEcheance = extraireChamp(dataAplati, "due_on");

        d.statut = "";
        String contenuMemberships = extraireContenuTableau(json, "memberships");
        List<String> objetsMemberships = decouperObjetsJson(contenuMemberships);
        if (!objetsMemberships.isEmpty()) {
            String premier = objetsMemberships.get(0);
            int idxSection = premier.indexOf("\"section\"");
            if (idxSection >= 0) {
                int debutSec = premier.indexOf("{", idxSection);
                int finSec = trouverFermetureBloc(premier, debutSec);
                if (debutSec >= 0 && finSec >= 0) {
                    d.statut = extraireChamp(premier.substring(debutSec, finSec + 1), "name");
                }
            }
        }

        String contenuCustom = extraireContenuTableau(json, "custom_fields");
        d.champsPersonnalises = new ArrayList<>();
        List<String> objetsCustom = decouperObjetsJson(contenuCustom);
        for (String obj : objetsCustom) {
            ChampPerso c = new ChampPerso();
            c.nom = extraireChamp(obj, "name");
            c.valeur = extraireChamp(obj, "display_value");
            int idxEnum = obj.indexOf("\"enum_value\"");
            if (idxEnum > 0) {
                String blocEnum = obj.substring(idxEnum);
                c.couleur = extraireChamp(blocEnum, "color");
                if (c.couleur.isEmpty()) c.couleur = "gray";
            } else {
                c.couleur = "gray";
            }
            if (!c.nom.isEmpty() && !c.valeur.isEmpty()
                    && !c.nom.equals(CHAMP_FX_ACCORD)) {
                classerChamp(c);
                d.champsPersonnalises.add(c);
            }
        }

        String contenuSub = extraireContenuTableau(json, "subtasks");
        d.sousTaches = new ArrayList<>();
        List<String> objetsSub = decouperObjetsJson(contenuSub);
        for (String obj : objetsSub) {
            String nomSt = extraireChamp(obj, "name");
            if (!nomSt.isEmpty()) d.sousTaches.add(nomSt);
        }
        return d;
    }

    private static void classerChamp(ChampPerso c) {
        String n = c.nom.trim();
        if (n.equals(CHAMP_TYPE_DEMANDEUR) || n.equals(CHAMP_NB_PARTICIPANTS) || n.equals(CHAMP_ESPACES)) {
            c.categorie = "INFO"; c.parentMoyen = ""; return;
        }
        if (n.equals(CHAMP_FORMATION_SPORTIVE) || n.equals(CHAMP_NETTOYAGE) || n.equals(CHAMP_MATERIEL)
                || n.equals(CHAMP_SERVICE_AV) || n.equals(CHAMP_SERVICE_RESTAURATION) || n.equals(CHAMP_RESTAURATION)) {
            c.categorie = "MOYEN"; c.parentMoyen = ""; return;
        }
        if (n.equals(CHAMP_MATERIELS_LISTE)) { c.categorie = "DETAIL"; c.parentMoyen = CHAMP_MATERIEL; return; }
        if (n.equals(CHAMP_MATERIEL_AV_LISTE)) { c.categorie = "DETAIL"; c.parentMoyen = CHAMP_SERVICE_AV; return; }
        c.categorie = "AUTRE"; c.parentMoyen = "";
    }

    private static List<ChampPerso> champsParCategorie(List<ChampPerso> tous, String cat) {
        List<ChampPerso> r = new ArrayList<>();
        for (ChampPerso c : tous) if (cat.equals(c.categorie)) r.add(c);
        return r;
    }

    private static List<ChampPerso> detailsDuMoyen(List<ChampPerso> tous, String nomMoyen) {
        List<ChampPerso> r = new ArrayList<>();
        for (ChampPerso c : tous) if ("DETAIL".equals(c.categorie) && nomMoyen.equals(c.parentMoyen)) r.add(c);
        return r;
    }

    private static String extraireTicketDepuisUrl(HttpExchange echange) {
        String query = echange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) return "";
        return extraireValeurFormulaire(query, "ticket");
    }

    private static String extraireValeurFormulaire(String corps, String nomChamp) {
        if (corps == null) return "";
        String[] paires = corps.split("&");
        for (String paire : paires) {
            String[] kv = paire.split("=", 2);
            if (kv.length == 2 && kv[0].equals(nomChamp)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return "";
    }

    private static String extraireContenuDataAplati(String json) {
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx < 0) return "";
        int braceStart = json.indexOf("{", dataIdx);
        if (braceStart < 0) return "";
        int braceEnd = trouverFermetureBloc(json, braceStart);
        if (braceEnd < 0) return "";
        String content = json.substring(braceStart + 1, braceEnd);
        StringBuilder result = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
                if (depth == 0) result.append(c);
                continue;
            }
            if (inString) { if (depth == 0) result.append(c); continue; }
            if (c == '{' || c == '[') { depth++; continue; }
            if (c == '}' || c == ']') { depth--; continue; }
            if (depth == 0) result.append(c);
        }
        return result.toString();
    }

    private static String extraireContenuTableau(String json, String cle) {
        int idx = json.indexOf("\"" + cle + "\"");
        if (idx < 0) return "";
        int debut = json.indexOf("[", idx);
        int fin = trouverFermetureBloc(json, debut);
        if (debut < 0 || fin < 0) return "";
        return json.substring(debut + 1, fin);
    }

    private static int trouverFermetureBloc(String s, int debut) {
        if (debut < 0 || debut >= s.length()) return -1;
        char ouverture = s.charAt(debut);
        char fermeture;
        if (ouverture == '{') fermeture = '}';
        else if (ouverture == '[') fermeture = ']';
        else return -1;
        int profondeur = 0;
        boolean dansChaine = false;
        for (int i = debut; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) dansChaine = !dansChaine;
            if (dansChaine) continue;
            if (c == ouverture) profondeur++;
            else if (c == fermeture) { profondeur--; if (profondeur == 0) return i; }
        }
        return -1;
    }

    private static List<String> decouperObjetsJson(String s) {
        List<String> r = new ArrayList<>();
        int profondeur = 0;
        int debut = -1;
        boolean dansChaine = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) dansChaine = !dansChaine;
            if (dansChaine) continue;
            if (c == '{') { if (profondeur == 0) debut = i; profondeur++; }
            else if (c == '}') {
                profondeur--;
                if (profondeur == 0 && debut >= 0) { r.add(s.substring(debut, i + 1)); debut = -1; }
            }
        }
        return r;
    }

    private static String extraireChamp(String s, String champ) {
        Pattern p = Pattern.compile("\"" + champ + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1);
        return "";
    }

    // ==================================================
    // HELPERS HTTP
    // ==================================================
    private static void redirigerVers(HttpExchange echange, String url) throws java.io.IOException {
        echange.getResponseHeaders().set("Location", url);
        echange.sendResponseHeaders(302, -1);
        echange.close();
    }

    private static void envoyerHtml(HttpExchange echange, String html) throws java.io.IOException {
        byte[] reponse = html.getBytes("UTF-8");
        echange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        echange.sendResponseHeaders(200, reponse.length);
        OutputStream os = echange.getResponseBody();
        os.write(reponse);
        os.close();
    }

    // ==================================================
    // CLASSES DE DONNEES
    // ==================================================
    static class Demande {
        String id, nom, notes, dateEcheance, statut;
        List<ChampPerso> champsPersonnalises;
        List<String> sousTaches;
    }
    static class ChampPerso {
        String nom, valeur, couleur, categorie, parentMoyen;
    }
    static class DemandeListe {
        String id, nom, dateEcheance, section;
    }

    // ==================================================
    // CONSTRUCTION PAGES HTML
    // ==================================================
    private static String construirePageErreur(String titre, String messageHtml) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'><title>").append(titre).append("</title>");
        html.append("<style>").append(getCss()).append("</style></head><body><div class='page'>");
        html.append("<div class='erreur'><h1 style='color:#A02834;font-size:20px;margin-bottom:10px;'>").append(titre).append("</h1>");
        html.append("<div>").append(messageHtml).append("</div></div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private static String construirePage(Demande d, String erreur, String emailConnecte) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'><title>Detail demande</title>");
        html.append("<style>").append(getCss()).append("</style></head><body><div class='page'>");

        // Barre du haut
        html.append("<div class='topbar'>");
        html.append("<div class='topbar-user'>Connecte en tant que <strong>").append(emailConnecte).append("</strong></div>");
        html.append("<a href='/logout' class='btn-logout'>Se deconnecter</a>");
        html.append("</div>");

        // Lien retour
        html.append("<a href='/dashboard' class='lien-retour'>← Retour aux demandes</a>");

        if (erreur != null) {
            html.append("<div class='erreur'><b>Erreur :</b> ").append(erreur).append("</div>");
        } else {
            html.append("<div class='entete'>");
            html.append("<div class='ticket'>Ticket #").append(d.id).append("</div>");
            html.append("<h1>").append(d.nom).append("</h1>");
            if (!d.statut.isEmpty()) html.append("<div class='statut-badge'>").append(d.statut).append("</div>");
            if (!d.dateEcheance.isEmpty()) html.append("<div class='meta'>Echeance : ").append(d.dateEcheance).append("</div>");
            html.append("</div>");

            List<ChampPerso> infos = champsParCategorie(d.champsPersonnalises, "INFO");
            if (!infos.isEmpty()) {
                html.append("<h2>Informations principales</h2><div class='infos-principales'>");
                for (ChampPerso c : infos) {
                    html.append("<div class='info-bloc'><div class='info-label'>").append(c.nom).append("</div>");
                    html.append("<div class='info-valeur'>").append(c.valeur).append("</div></div>");
                }
                html.append("</div>");
            }

            List<ChampPerso> moyens = champsParCategorie(d.champsPersonnalises, "MOYEN");
            if (!moyens.isEmpty()) {
                html.append("<h2>Moyens demandes</h2><div class='moyens-grille'>");
                for (ChampPerso moyen : moyens) {
                    html.append("<div class='moyen-bloc'><div class='moyen-titre'>");
                    html.append("<span class='champ-nom'>").append(moyen.nom).append("</span>");
                    html.append("<span class='pastille pastille-").append(moyen.couleur).append("'>").append(moyen.valeur).append("</span>");
                    html.append("</div>");
                    for (ChampPerso det : detailsDuMoyen(d.champsPersonnalises, moyen.nom)) {
                        html.append("<div class='moyen-detail'><span class='detail-label'>").append(det.nom).append(" :</span> ");
                        html.append("<span class='detail-valeur'>").append(det.valeur).append("</span></div>");
                    }
                    html.append("</div>");
                }
                html.append("</div>");
            }

            html.append("<h2>Etapes de validation</h2>");
            if (d.sousTaches.isEmpty()) {
                html.append("<div class='vide'>Aucune etape de validation.</div>");
            } else {
                html.append("<ul class='etapes'>");
                for (String etape : d.sousTaches) html.append("<li>").append(etape).append("</li>");
                html.append("</ul>");
            }

            List<ChampPerso> autres = champsParCategorie(d.champsPersonnalises, "AUTRE");
            if (!autres.isEmpty()) {
                html.append("<h2>Autres informations</h2><div class='champs'>");
                for (ChampPerso c : autres) {
                    html.append("<div class='champ'><span class='champ-nom'>").append(c.nom).append(" :</span> ");
                    html.append("<span class='pastille pastille-").append(c.couleur).append("'>").append(c.valeur).append("</span></div>");
                }
                html.append("</div>");
            }

            html.append("<h2>Ajouter un commentaire</h2>");
            html.append("<form class='form-commentaire' method='POST' action='/commentaire'>");
            html.append("<input type='hidden' name='ticket' value='").append(d.id).append("'>");
            html.append("<textarea name='texte' rows='4' placeholder='Votre commentaire...' required></textarea>");
            html.append("<button type='submit'>Envoyer le commentaire</button>");
            html.append("</form>");
        }
        html.append("</div></body></html>");
        return html.toString();
    }

    // ==================================================
    // CSS COMMUN
    // ==================================================
    private static String getCss() {
        return "* { margin: 0; padding: 0; box-sizing: border-box; }"
            + "body { font-family: Arial, sans-serif; background: #F4F6F9; color: #212529; padding: 30px; }"
            + ".page { max-width: 900px; margin: 0 auto; background: white; border-radius: 8px; padding: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.06); }"
            // Topbar (dashboard + detail)
            + ".topbar { display: flex; justify-content: space-between; align-items: center; padding-bottom: 16px; margin-bottom: 16px; border-bottom: 1px solid #E9ECEF; font-size: 13px; color: #495057; }"
            + ".topbar-user strong { color: #1A3A52; }"
            + ".btn-logout { background: #F4F6F9; color: #1A3A52; padding: 6px 14px; border-radius: 6px; text-decoration: none; font-size: 12px; font-weight: bold; border: 1px solid #DEE2E6; }"
            + ".btn-logout:hover { background: #1A3A52; color: white; }"
            // Login page
            + ".page-login { max-width: 420px; margin: 80px auto; }"
            + ".login-card { background: white; border-radius: 8px; padding: 40px 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.06); border-top: 4px solid #1A3A52; }"
            + ".login-titre { color: #1A3A52; font-size: 24px; text-align: center; margin-bottom: 4px; }"
            + ".login-sous { color: #6C757D; text-align: center; font-size: 14px; margin-bottom: 30px; }"
            + ".form-login { display: flex; flex-direction: column; gap: 10px; }"
            + ".form-login label { font-size: 13px; color: #495057; font-weight: bold; }"
            + ".form-login input { padding: 10px 12px; border: 1px solid #DEE2E6; border-radius: 6px; font-size: 14px; }"
            + ".form-login input:focus { outline: none; border-color: #2E75B6; }"
            + ".form-login button { background: #1A3A52; color: white; border: none; padding: 12px; border-radius: 6px; font-size: 14px; font-weight: bold; cursor: pointer; margin-top: 6px; }"
            + ".form-login button:hover { background: #2E75B6; }"
            + ".erreur-login { background: #F8D7DA; color: #A02834; padding: 10px; border-radius: 6px; font-size: 13px; margin-bottom: 16px; }"
            + ".login-separateur { text-align: center; color: #ADB5BD; font-size: 12px; margin: 24px 0 12px; }"
            + ".login-ticket-info { text-align: center; color: #ADB5BD; font-size: 13px; padding: 10px; background: #F8F9FA; border-radius: 6px; }"
            // Dashboard
            + ".page-titre { color: #1A3A52; font-size: 26px; margin-bottom: 6px; }"
            + ".compteur { color: #6C757D; font-size: 14px; margin-bottom: 20px; }"
            + ".section-titre { color: #1A3A52; font-size: 16px; margin: 24px 0 12px; }"
            + ".section-count { background: #2E75B6; color: white; padding: 2px 10px; border-radius: 10px; font-size: 12px; margin-left: 6px; }"
            + ".cartes-grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }"
            + ".carte { display: block; background: #F8F9FA; border-radius: 8px; padding: 16px; text-decoration: none; color: inherit; border-left: 3px solid #2E75B6; transition: transform 0.15s, box-shadow 0.15s; }"
            + ".carte:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.08); }"
            + ".carte-ticket { font-size: 11px; color: #6C757D; margin-bottom: 4px; }"
            + ".carte-nom { color: #1A3A52; font-weight: bold; font-size: 15px; margin-bottom: 8px; }"
            + ".carte-date { font-size: 13px; color: #495057; margin-bottom: 8px; }"
            + ".carte-cta { font-size: 12px; color: #2E75B6; font-weight: bold; }"
            + ".message-vide { text-align: center; padding: 60px 20px; color: #6C757D; }"
            + ".message-vide-sub { font-size: 13px; margin-top: 8px; color: #ADB5BD; }"
            // Detail (idem qu'avant)
            + ".lien-retour { display: inline-block; color: #2E75B6; text-decoration: none; font-size: 13px; margin-bottom: 16px; }"
            + ".lien-retour:hover { text-decoration: underline; }"
            + ".entete { border-left: 4px solid #2E75B6; padding-left: 16px; margin-bottom: 24px; }"
            + ".ticket { font-size: 12px; color: #6C757D; margin-bottom: 4px; }"
            + ".entete h1 { color: #1A3A52; font-size: 24px; margin-bottom: 8px; }"
            + ".meta { font-size: 13px; color: #495057; margin-bottom: 8px; }"
            + ".statut-badge { display: inline-block; background: #1A3A52; color: white; padding: 4px 12px; border-radius: 12px; font-size: 12px; font-weight: bold; margin: 6px 0; text-transform: uppercase; letter-spacing: 0.3px; }"
            + "h2 { color: #1A3A52; font-size: 16px; margin: 22px 0 10px; }"
            + ".infos-principales { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 8px; }"
            + ".info-bloc { background: linear-gradient(135deg, #1A3A52, #2E75B6); color: white; padding: 14px 18px; border-radius: 8px; flex: 1; min-width: 180px; }"
            + ".info-label { font-size: 11px; text-transform: uppercase; opacity: 0.85; letter-spacing: 0.5px; margin-bottom: 4px; }"
            + ".info-valeur { font-size: 18px; font-weight: bold; }"
            + ".moyens-grille { display: flex; flex-direction: column; gap: 8px; }"
            + ".moyen-bloc { background: #F8F9FA; border-radius: 6px; padding: 12px 16px; }"
            + ".moyen-titre { display: flex; justify-content: space-between; align-items: center; }"
            + ".moyen-detail { font-size: 13px; color: #495057; margin-top: 8px; padding: 6px 10px; border-left: 2px solid #2E75B6; background: white; border-radius: 4px; }"
            + ".detail-label { font-weight: bold; color: #495057; }"
            + ".etapes { list-style: none; }"
            + ".etapes li { background: #EAF2F9; border-left: 3px solid #2E75B6; padding: 10px 14px; margin-bottom: 6px; border-radius: 4px; font-size: 14px; }"
            + ".champs { background: #F8F9FA; border-radius: 6px; padding: 14px; }"
            + ".champ { padding: 6px 0; font-size: 14px; }"
            + ".champ-nom { font-weight: bold; color: #495057; }"
            + ".pastille { display: inline-block; padding: 3px 12px; border-radius: 12px; font-size: 12px; font-weight: bold; margin-left: 6px; }"
            + ".pastille-green { background: #D4EDDA; color: #1E7E34; }"
            + ".pastille-red { background: #F8D7DA; color: #A02834; }"
            + ".pastille-orange { background: #FFE5B4; color: #B7791F; }"
            + ".pastille-yellow { background: #FFF3CD; color: #B7791F; }"
            + ".pastille-blue { background: #D6E4F0; color: #1F5C9A; }"
            + ".pastille-gray { background: #E9ECEF; color: #495057; }"
            + ".vide { color: #8896A6; font-style: italic; font-size: 13px; padding: 10px 0; }"
            + ".form-commentaire { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }"
            + ".form-commentaire textarea { width: 100%; padding: 10px; border: 1px solid #DEE2E6; border-radius: 6px; font-family: inherit; font-size: 14px; resize: vertical; }"
            + ".form-commentaire textarea:focus { outline: none; border-color: #2E75B6; }"
            + ".form-commentaire button { align-self: flex-start; background: #1A3A52; color: white; border: none; padding: 10px 22px; border-radius: 6px; font-size: 14px; font-weight: bold; cursor: pointer; }"
            + ".form-commentaire button:hover { background: #2E75B6; }"
            + ".erreur { background: #F8D7DA; color: #A02834; padding: 14px; border-radius: 4px; }";
    }
}