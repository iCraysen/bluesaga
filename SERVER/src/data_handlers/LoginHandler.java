package data_handlers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map.Entry;

import character_info.CharacterCreate;
import utils.DateCalc;
import utils.ServerGameInfo;
import utils.ServerMessage;
import utils.TimeUtils;
import utils.WebHandler;
import data_handlers.chat_handler.ChatHandler;
import data_handlers.item_handler.Item;
import game.ServerSettings;
import login.WebsiteLogin;
import network.Client;
import network.Server;

public class LoginHandler extends Handler {

  public static void init() {
    DataHandlers.register("login", m -> handleLogin(m));
    DataHandlers.register("logout", m -> handleLogout(m));
    DataHandlers.register("newchar", m -> handleNewCharacter(m));
    DataHandlers.register("createchar", m -> handleCreateCharacter(m));
    DataHandlers.register("deletechar", m -> handleDeleteCharacter(m));
    DataHandlers.register("changepassword", m -> handleChangePassword(m));
  }

  public static void handleLogin(Message m) {
    Client client = m.client;
    String[] loginInfo = m.message.split(";");

    int newUserId = 0;

    if (ServerSettings.DEV_MODE) {
      if (Server.clients.size() == 1) {
        newUserId = 2;
      } else if (Server.clients.size() == 2) {
        newUserId = 1;
      }

      client.ConfirmAccount = true;
    } else {
      String mail = loginInfo[0];
      String password = loginInfo[1];

      newUserId = WebsiteLogin.Login(mail, password, client.IP);

      // If not correct
      if (newUserId <= 0) {
        if (newUserId == 0) {
          Handler.addOutGoingMessage(client, "login", "wrong");
        } else if (newUserId == -1) {
          ServerMessage.printMessage("Can't connect to bluesaga.org!", false);
          Handler.addOutGoingMessage(client, "login", "error,#messages.server.has_problems");
        } else if (newUserId == -2) {
          addOutGoingMessage(client, "login", "needconfirm");
        }
        client.RemoveMe = true;
      }
    }

    if (newUserId > 0) {
      client.ConfirmAccount = true;
      boolean hasPKChar = false;
      int pkCharId = 0;

      // CHECK IF USER ALREADY LOGGED IN
      for (Entry<Integer, Client> entry : Server.clients.entrySet()) {
        Client s = entry.getValue();

        if (newUserId == s.UserId) {

          // CHECK IF PLAYER IS PK
          if (s.playerCharacter != null) {
            if (s.playerCharacter.getPkMarker() > 0) {
              hasPKChar = true;
              pkCharId = s.playerCharacter.getDBId();
            }
          }

          addOutGoingMessage(s, "backtologin", "Account used on another computer");

          // LOGOUT OTHER USER
          s.RemoveMe = true;

          break;
        }
      }

      client.UserId = newUserId;
      client.UserMail = loginInfo[0];

      // GET CHEST SIZE
      // IF NOT SET, CREATE A 4x4 CHEST
      ResultSet chestInfo =
          Server.userDB.askDB(
              "select ChestSize from user_settings where UserId = " + client.UserId);
      try {
        if (chestInfo.next()) {
          client.chestSize = chestInfo.getInt("ChestSize");
        } else {
          client.chestSize = 6;
          Server.userDB.updateDB(
              "insert into user_settings (UserId,ChestSize) values("
                  + client.UserId
                  + ","
                  + client.chestSize
                  + ")");
        }
        chestInfo.close();
      } catch (SQLException e) {
        e.printStackTrace();
      }

      ServerMessage.printMessage(
          TimeUtils.now() + ": " + client.UserMail + " logged in successfully!", false);

      // SKICKA NAMN, RAS, EQUIP, LEVEL
      // FOR ALLA KARAKTARER TILL SPELAREN
      String character_info = getCharacterInfo(client);

      addOutGoingMessage(
          client, "login", client.UserId + ";" + client.UserMail + ":" + character_info);

      if (hasPKChar) {
        // IF ACCOUNT HAS PK CHAR
        // LOGIN TO PK CHAR DIRECTLY

        ServerMessage.printMessage(
            "Player has a PK char logged in already! Sending that char...", false);

        if (pkCharId > 0) {
          ConnectHandler.sendPlayerCharacterInfo(client, pkCharId);
        }
      }
    }
  }

  public static void handleLogout(Message m) {
    Client client = m.client;
    ConnectHandler.logoutCharacter(client);
    client.UserId = 0;
    client.UserMail = "";
  }

  // SEND INFO ABOUT FAMILIES, CLASSES
  public static void handleNewCharacter(Message m) {
    Client client = m.client;
    StringBuilder newInfo = new StringBuilder(1000);

    newInfo.append("1,Family Creatures/");

    // CreatureId, FamilyName; CreaureId2, FamilyName2 / ItemId; ItemId2
    ResultSet creatureInfo =
        Server.gameDB.askDB(
            "select Id, Name, ClassId from creature where PlayerCreature = 1 and FamilyId = 1 and Level = 1");
    try {
      while (creatureInfo.next()) {
        newInfo
            .append(creatureInfo.getString("Id"))
            .append(',')
            .append(creatureInfo.getString("Name"))
            .append(';');
      }
      creatureInfo.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }

    addOutGoingMessage(client, "newchar", newInfo.toString());
  }

  public static void handleCreateCharacter(Message m) {
    Client client = m.client;
    String CharInfo[] = m.message.split(";");

    boolean createOk = true;

    String charName = CharInfo[0];

    int creatureId = 19;
    int classId = 2;

    if (CharInfo.length == 3) {
      try {
        creatureId = Integer.parseInt(CharInfo[1]);
        classId = Integer.parseInt(CharInfo[2]);

        if (classId < 1 || classId > 3) {
          createOk = false;
        }
      } catch (NumberFormatException nfe) {
        createOk = false;
      }
    } else {
      createOk = false;
    }

    if (createOk) {
      int charId = createCharacter(client, charName, creatureId, classId);

      if (charId > 0) {
        addOutGoingMessage(client, "createchar", String.valueOf(charId));
      } else if (charId == 0) {
        addOutGoingMessage(client, "createchar", "exists");
      } else if (charId < 0) {
        addOutGoingMessage(client, "createchar", "incorrect");
      }
    } else {
      addOutGoingMessage(client, "createchar", "error");
    }
  }

  public static void handleDeleteCharacter(Message m) {
    Client client = m.client;
    int characterId = Integer.parseInt(m.message);

    // CHECK IF USER OWNS CHARACTER
    if (Server.userDB.checkCharacterOwnership(characterId, client.UserId)) {

      ResultSet charInfo =
          Server.userDB.askDB(
              "select Deleted from user_character where UserId = "
                  + client.UserId
                  + " and Id = "
                  + characterId);
      try {
        if (charInfo.next()) {
          if (charInfo.getString("Deleted").equals("No")) {
            Server.userDB.updateDB(
                "update user_character set Deleted = '"
                    + TimeUtils.now()
                    + "' where Id = "
                    + characterId);
            addOutGoingMessage(client, "deletechar", characterId + ",0");
          } else {
            Server.userDB.updateDB(
                "update user_character set Deleted = 'No' where Id = " + characterId);
            addOutGoingMessage(client, "deletechar", characterId + ",-1");
          }
        }
        charInfo.close();
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }
  }

  public static void handleChangePassword(Message m) {
    Client client = m.client;
    String passwordInfo[] = m.message.split(";");

    String oldPass = passwordInfo[0];
    String newPass = passwordInfo[1];

    int statusCode = WebsiteLogin.UpdatePassword(client.UserMail, oldPass, newPass, client.IP);
    if (statusCode == 1) {
      Handler.addOutGoingMessage(client, "passwordchanged", "ok");
    } else if (statusCode == -1) {
      Handler.addOutGoingMessage(client, "passwordchanged", "wrongpass");
    }
  }

  /*
   *
   * 	CREATE & LOAD CHARACTER
   *
   */

  public static int createCharacter(Client client, String name, int creatureId, int classId) {
    int charId = 0;

    boolean correctName = true;
    int blueSagaId = 0;

    if (ServerSettings.DEV_MODE) {
      if (!name.matches("[A-Za-z]+")) {
        return 0;
      }
      for (String badword : ChatHandler.BadWords) {
        if (name.contains(badword)) {
          return 0;
        }
      }
      ResultSet charInfo = Server.userDB.askDB("select Id from user_character where Name like '" + name + "'");
      try {
        if (charInfo.next()) {
          return 0;
        }
      } catch (SQLException ex) {
        ex.printStackTrace();
        return 0;
      }
    }
    else {
      //SEND NAME TO WEBSITE API
      // Checks if name doesnt contain a bad word
      // Checks if name only contains letters
      // Checks if name already is taken
      int statusCode =
          CharacterCreate.CreateCharacter(ServerSettings.SERVER_ID, client.UserId, creatureId, name);

      if (statusCode <= 0) {
        correctName = false;
      } else {
        blueSagaId = statusCode;
      }
      System.out.println("NEW BLUESAGA ID: " + blueSagaId);
    }

    if (correctName) {

      // SAVE CHARACTER INFO
      StringBuilder sqlStatement = new StringBuilder(1000);
      sqlStatement
          .append(
              "insert into user_character (UserId, CreatureId, Level, XP, HEALTH, MANA, X, Y, Z, Name, PlayerKiller, Bounty, CheckpointId, CreatedOn, ShipId, AreaEffectId, Deleted, LastOnline, MouthFeatureId, AccessoriesId, SkinFeatureId, HeadSkinId, WeaponSkinId, OffHandSkinId, AmuletSkinId, ArtifactSkinId, AdminLevel, Muted, InventorySize, TutorialNr, BaseClassId, BaseCreatureId, BlueSagaId) values (")
          .append(client.UserId)
          .append(',')
          .append(creatureId)
          .append(",1,0,");

      int health = 0;
      int mana = 0;

      health = ServerGameInfo.classDef.get(classId).getStartStats().getValue("MAX_HEALTH");
      mana = ServerGameInfo.classDef.get(classId).getStartStats().getValue("MAX_MANA");

      sqlStatement.append(health).append(',').append(mana).append(",5005,9984,2,"); // X, Y, Z

      // FIX NAME SO THAT ALL LETTERS ARE LOWER CASE EXCEPT FIRST LETTER
      String firstLetter = name.substring(0, 1).toUpperCase();
      String restLetters = name.substring(1).toLowerCase();
      String fixedName = firstLetter + restLetters;

      sqlStatement
          .append('\'')
          .append(fixedName) // CHARACTER NAME
          .append("',")
          .append("0,0,") // PLAYER KILLER / BOUNTY
          .append("1,'") // CheckpointId
          .append(TimeUtils.now()) // CreatedOn
          .append("',0,0,'No',") // ShipId, AreaEffectId, Deleted
          .append('\'')
          .append(TimeUtils.now()) // LastOnline
          .append(
              "',0,0,0,0,0,0,0,0,") // MouthFeatureId, AccessoriesId, SkinFeatureId, HeadSkinId, WeaponSkinId, OffHandSkinId, AmuletSkinId, ArtifactSkinId
          .append("0,'No',4,0,") //AdminLevel, Muted, InventorySize, TutorialNr
          .append(classId)
          .append(',') // BaseClassId
          .append(creatureId)
          .append(',') // BaseCreatureId
          .append(blueSagaId)
          .append(')'); // BlueSagaId

      try {
        Server.userDB.updateDB(sqlStatement.toString());

        ResultSet newChar =
            Server.userDB.askDB("select Id from user_character order by Id desc limit 1");
        if (newChar.next()) {
          charId = newChar.getInt("Id");
        }
        newChar.close();

        if (classId == 1) {

          // ADD WOODEN CLUB
          Server.userDB.updateDB(
              "insert into character_item (CharacterId, ItemId, Equipped, InventoryPos) values ("
                  + charId
                  + ",8,1,'None')");

          // ADD POWER STRIKE ABILITY
          Server.userDB.updateDB(
              "insert into character_ability (CharacterId, AbilityId, CooldownLeft) values ("
                  + charId
                  + ",17,0)");

          // ADD ABILITY TO ACTIONBAR
          Server.userDB.updateDB(
              "insert into character_actionbar (ActionType, ActionId, CharacterId, OrderNr) values ('Ability',17,"
                  + charId
                  + ",0)");
        } else if (classId == 3) {
          // ADD WOODEN SLINGSHOT
          Server.userDB.updateDB(
              "insert into character_item (CharacterId, ItemId, Equipped, InventoryPos) values ("
                  + charId
                  + ",14,1,'None')");
          // ADD FOCUSED SHOT ABILITY
          Server.userDB.updateDB(
              "insert into character_ability (CharacterId, AbilityId, CooldownLeft) values ("
                  + charId
                  + ",54,0)");
          // ADD ABILITY TO ACTIONBAR
          Server.userDB.updateDB(
              "insert into character_actionbar (ActionType, ActionId, CharacterId, OrderNr) values ('Ability',54,"
                  + charId
                  + ",0)");
        } else if (classId == 2) {

          // ADD ENERGY BURST
          Server.userDB.updateDB(
              "insert into character_ability (CharacterId, AbilityId, CooldownLeft) values ("
                  + charId
                  + ",73,0)");

          // ADD ABILITY TO ACTIONBAR
          // ADD MAGIC WAND
          Server.userDB.updateDB(
              "insert into character_item (CharacterId, ItemId, Equipped, InventoryPos) values ("
                  + charId
                  + ",231,1,'None')");
          Server.userDB.updateDB(
              "insert into character_actionbar (ActionType, ActionId, CharacterId, OrderNr) values ('Ability',73,"
                  + charId
                  + ",0)");
        }

        Server.userDB.updateDB(
            "insert into character_quest (QuestId, CharacterId, Status) values (30,"
                + charId
                + ",1)");
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }
    return charId;
  }

  public static String getCharacterInfo(Client client) {
    StringBuilder character_info = new StringBuilder(1000);
    character_info.append("None");

    ResultSet characterRS =
        Server.userDB.askDB(
            "select * from user_character where UserId = "
                + client.UserId
                + " and Deleted <> 'Yes' order by Id asc");

    int nrCharacters = 0;

    try {
      while (characterRS.next()) {

        ResultSet creatureRS =
            Server.gameDB.askDB(
                "select * from creature where Id = " + characterRS.getInt("CreatureId"));

        if (creatureRS.next()) {
          boolean charAvailable = true;
          int deleted = -1;

          if (!characterRS.getString("Deleted").equals("No")) {
            // DELETE CHARACTERS THAT ARE SET TO BE DELETED
            // AFTER 3 DAYS
            DateCalc dateCalculator = new DateCalc();

            String startDate = characterRS.getString("Deleted");
            String endDate = TimeUtils.now();
            int nrDays = dateCalculator.daysBetween(startDate, endDate);

            if (nrDays > 2) {
              charAvailable = false;
              Server.userDB.updateDB(
                  "update user_character set Deleted = 'Yes' where Id = "
                      + characterRS.getInt("Id"));
            } else {
              deleted = nrDays;
            }
          }

          if (charAvailable) {
            if (nrCharacters > 0) {
              character_info.append('/');
            } else {
              character_info.setLength(0);
            }

            // CreatureId; Name; Level; AreaId; HeadId; WeaponId; OffHandId; AmuletId; ArtifactId;
            character_info
                .append(characterRS.getInt("Id"))
                .append(';')
                .append(characterRS.getInt("CreatureId"))
                .append(';')
                .append(characterRS.getString("Name"))
                .append(';')
                .append(characterRS.getInt("Level"))
                .append(';')
                .append(characterRS.getInt("MouthFeatureId"))
                .append(';')
                .append(characterRS.getInt("AccessoriesId"))
                .append(';')
                .append(characterRS.getString("SkinFeatureId"))
                .append(';');

            ResultSet equipRS =
                Server.userDB.askDB(
                    "select ItemId from character_item where CharacterId = "
                        + characterRS.getInt("Id")
                        + " and Equipped = 1");

            Item checkItem = null;
            int headItemId = characterRS.getInt("HeadSkinId");
            int weaponItemId = characterRS.getInt("WeaponSkinId");
            int offhandItemId = characterRS.getInt("OffHandSkinId");
            int amuletItemId = characterRS.getInt("AmuletSkinId");
            int artifactItemId = characterRS.getInt("ArtifactSkinId");

            while (equipRS.next()) {
              checkItem = new Item(ServerGameInfo.itemDef.get(equipRS.getInt("ItemId")));
              if (checkItem.getType().equals("Head") && headItemId == 0) {
                headItemId = checkItem.getId();
              } else if (checkItem.getType().equals("Weapon") && weaponItemId == 0) {
                weaponItemId = checkItem.getId();
              } else if (checkItem.getType().equals("OffHand") && offhandItemId == 0) {
                offhandItemId = checkItem.getId();
              } else if (checkItem.getType().equals("Amulet") && amuletItemId == 0) {
                amuletItemId = checkItem.getId();
              } else if (checkItem.getType().equals("Artifact") && artifactItemId == 0) {
                artifactItemId = checkItem.getId();
              }
            }
            equipRS.close();

            // GET SKINS

            character_info
                .append(headItemId)
                .append(';')
                .append(weaponItemId)
                .append(';')
                .append(offhandItemId)
                .append(';')
                .append(amuletItemId)
                .append(';')
                .append(artifactItemId)
                .append(';')
                .append(creatureRS.getInt("HeadX"))
                .append(';')
                .append(creatureRS.getInt("HeadY"))
                .append(';')
                .append(creatureRS.getInt("WeaponX"))
                .append(';')
                .append(creatureRS.getInt("WeaponY"))
                .append(';')
                .append(creatureRS.getInt("OffHandX"))
                .append(';')
                .append(creatureRS.getInt("OffHandY"))
                .append(';')
                .append(creatureRS.getInt("AmuletX"))
                .append(';')
                .append(creatureRS.getInt("AmuletY"))
                .append(';')
                .append(creatureRS.getInt("ArtifactX"))
                .append(';')
                .append(creatureRS.getInt("ArtifactY"))
                .append(';')
                .append(creatureRS.getInt("MouthFeatureX"))
                .append(';')
                .append(creatureRS.getInt("MouthFeatureY"))
                .append(';')
                .append(creatureRS.getInt("AccessoriesX"))
                .append(';')
                .append(creatureRS.getInt("AccessoriesY"))
                .append(';')
                .append(creatureRS.getInt("SkinFeatureX"))
                .append(';')
                .append(creatureRS.getInt("SkinFeatureY"))
                .append(';')
                .append(deleted);

            nrCharacters++;
          }
        }
        creatureRS.close();
      }
      characterRS.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }

    return character_info.toString();
  }
}
