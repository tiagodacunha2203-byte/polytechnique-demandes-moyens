# Interface Demandes de Moyens — École Polytechnique

> Application web de suivi des demandes de moyens pour les demandeurs internes de l'École Polytechnique, synchronisée avec Asana via API REST.

[![BTS SIO SLAM](https://img.shields.io/badge/BTS_SIO-SLAM-blue)](https://github.com/tiagodacunha2203-byte)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Asana API](https://img.shields.io/badge/API-Asana-FC636B?logo=asana&logoColor=white)](https://developers.asana.com/)
[![Status](https://img.shields.io/badge/Status-Prototype-yellow)]()


## À propos

Projet développé dans le cadre de mon **stage de 1ère année BTS SIO SLAM** chez **ONAGIT** (prestataire IT de l'École Polytechnique), du 13 mai au 17 juin 2026.

### Le problème

L'École Polytechnique gère ses demandes internes de moyens (salles, restauration, matériel audiovisuel, encadrement sportif...) via un formulaire Asana qui alimente un projet centralisé. Les équipes de Polytechnique traitent les demandes directement dans Asana, mais **le demandeur n'a aucune visibilité sur l'avancement de sa demande** une fois soumise.

### La solution

Une **interface web dédiée au demandeur**, synchronisée avec Asana via leur API publique, qui lui donne une vision claire et autonome de l'état de ses demandes :

-  Connexion par adresse e-mail
-  Dashboard affichant uniquement ses propres demandes, classées par statut
-  Page de détail avec toutes les informations et les étapes de validation
-  Possibilité de poster un commentaire qui apparaît directement dans Asana

---

## Stack technique

| Couche | Technologie |
|---|---|
| **Backend** | Java 25 natif (HttpServer, HttpClient, sans framework externe) |
| **Frontend** | HTML5, CSS3, JavaScript natif (sans framework) |
| **API externe** | Asana API REST v1.0 |
| **Authentification** | Sessions par cookie HttpOnly avec SecureRandom |
| **Format d'échange** | JSON (parsing manuel par regex) |

---

## Fonctionnalités

### Écran 1 — Connexion
- Authentification par adresse e-mail
- Création d'une session sécurisée (cookie HttpOnly)
- Mode "numéro de ticket" prévu pour les utilisateurs externes

### Écran 2 — Dashboard
- Liste des demandes de l'utilisateur connecté uniquement
- Classement automatique par section Asana (Nouvelles, En cours, etc.)
- Filtrage strict par e-mail du demandeur

### Écran 3 — Détail
- Affichage structuré : Informations principales / Moyens demandés / Étapes de validation
- Hiérarchie parent/enfant pour les moyens (ex : Matériel → liste détaillée)
- Badge de statut visible
- Formulaire d'ajout de commentaire vers Asana

---

## Sécurité

Le projet implémente plusieurs mécanismes de sécurité applicative :

- **Sessions imprévisibles** : `SecureRandom` génère des identifiants de 48 caractères hexadécimaux
- **Cookies HttpOnly** : inaccessibles au JavaScript pour prévenir les attaques XSS
- **Pas d'identifiant utilisateur dans l'URL** : évite les fuites par historique, logs ou referer
- **Anti-tampering (IDOR)** : vérification que la ressource demandée appartient bien à l'utilisateur
- **Échappement JSON** : prévention des injections dans les commentaires POST
- **Token API externalisé** : aucune donnée sensible dans le code source


## Installation et lancement

### Prérequis

- **Java 25** ou supérieur (Temurin recommandé)
- Un **token API Asana** (Personal Access Token, à obtenir sur https://app.asana.com/0/my-apps)
- L'**identifiant** d'un projet Asana à interroger

### Étapes

1. **Cloner le dépôt**
```bash
   git clone https://github.com/tiagodacunha2203-byte/interface-demandes-moyens-polytechnique.git
   cd interface-demandes-moyens-polytechnique
```

2. **Configurer**
```bash
   # Copier le fichier d'exemple et le remplir
   cp config.properties.example config.properties
   # Editer config.properties avec votre token et votre project ID
```

3. **Compiler**
```bash
   javac Ecran3Demande.java
```

4. **Lancer**
```bash
   java Ecran3Demande
```

5. **Accéder à l'application**
   
   Ouvrir un navigateur sur : `http://localhost:8080`

---

## Structure du projet
interface-demandes-moyens-polytechnique/

Ecran3Demande.java ← Serveur principal 

MiniTestAsana.java ← Premier prototype (console)

config.properties.example ← Modèle de configuration

.gitignore ← Fichiers exclus du dépôt

README.md ← Ce fichier

> Note : le fichier `config.properties` (contenant le token) est intentionnellement absent du dépôt pour des raisons de sécurité. Voir la section Installation pour le créer.


## Compétences mobilisées (Référentiel BTS SIO SLAM)

| Bloc | Compétence | Niveau |

| B1.3 | Développer la présence en ligne de l'organisation | Application |
| B1.4 | Travailler en mode projet | Maîtrise |
| B1.6 | Organiser son développement professionnel | Maîtrise |
| B2.1 | Concevoir et développer une solution applicative | Maîtrise |
| B2.2 | Assurer la maintenance corrective ou évolutive | Application |
| B2.3 | Gérer les données | Application |
| B3.6 | Cybersécurité d'une solution applicative | Application |

---

## Limitations connues et perspectives

Ce projet est un **prototype** livré en fin de stage. Les évolutions suivantes sont prévues pour la mise en production :

- [ ] Migration vers Tomcat / JSP / Servlets pour utiliser les fichiers HTML/CSS de la maquette
- [ ] Intégration au SSO Polytechnique en remplacement du login par e-mail
- [ ] Utilisation d'un compte de service Asana pour signer les commentaires
- [ ] Stockage du token en variable d'environnement
- [ ] Mise en place de tests automatisés (JUnit)
- [ ] Optimisation des appels API (caching, batch)

---

## 👤 Auteur

**Tiago DA CUNHA**  
Étudiant BTS SIO SLAM — Lycée Charles de Foucauld, Paris 18e  
Stage chez ONAGIT, Mai-Juin 2026
