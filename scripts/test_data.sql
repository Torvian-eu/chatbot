PRAGMA foreign_keys=OFF;
BEGIN TRANSACTION;
CREATE TABLE api_secrets (alias VARCHAR(36) NOT NULL PRIMARY KEY, encrypted_credential TEXT NOT NULL, wrapped_dek VARCHAR(255) NOT NULL, key_version INT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, CONSTRAINT chk_api_secrets_signed_integer_key_version CHECK (key_version BETWEEN -2147483648 AND 2147483647));
CREATE TABLE llm_providers (id INTEGER PRIMARY KEY AUTOINCREMENT, api_key_id VARCHAR(255) NULL, "name" VARCHAR(255) NOT NULL, description TEXT NOT NULL, base_url VARCHAR(500) NOT NULL, "type" VARCHAR(50) NOT NULL);
INSERT INTO llm_providers VALUES(1,NULL,'Ollama','Ollama Local','http://localhost:11434','OLLAMA');
CREATE TABLE llm_models (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            "name" VARCHAR(255) NOT NULL,
                            provider_id BIGINT NOT NULL,
                            active BOOLEAN DEFAULT 1 NOT NULL,
                            display_name VARCHAR(255) NULL,
                            "type" VARCHAR(50) NOT NULL,
                            capabilities TEXT NULL,
                            CONSTRAINT fk_llm_models_provider_id__id FOREIGN KEY (provider_id) REFERENCES llm_providers(id) ON DELETE CASCADE ON UPDATE RESTRICT
);
INSERT INTO llm_models VALUES(1,'qwen3:latest',1,1,'qwen3-8b','CHAT',NULL);
INSERT INTO llm_models VALUES(2,'gemma3:latest',1,1,'gemma3-5b','CHAT',NULL);
CREATE TABLE model_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_id BIGINT NOT NULL,
    "name" VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    variable_params_json TEXT NOT NULL,
    custom_params_json TEXT NULL,
    CONSTRAINT fk_model_settings_model_id__id FOREIGN KEY (model_id) REFERENCES llm_models(id) ON DELETE CASCADE ON UPDATE RESTRICT
);
INSERT INTO model_settings VALUES(1,1,'Default','CHAT','{}',NULL);
INSERT INTO model_settings VALUES(2,2,'Default','CHAT','{}',NULL);
CREATE TABLE chat_groups (id INTEGER PRIMARY KEY AUTOINCREMENT, "name" VARCHAR(255) NOT NULL, created_at BIGINT DEFAULT 1754505428820 NOT NULL);
INSERT INTO chat_groups VALUES(22,'Work Discussions',1754248607000);
INSERT INTO chat_groups VALUES(23,'Personal Chats',1754335007000);
INSERT INTO chat_groups VALUES(24,'Tech Q&A',1754421407000);
CREATE TABLE chat_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, "name" VARCHAR(255) NOT NULL, created_at BIGINT DEFAULT 1754505428820 NOT NULL, updated_at BIGINT DEFAULT 1754505428820 NOT NULL, group_id BIGINT NULL, current_model_id BIGINT NULL, current_settings_id BIGINT NULL, CONSTRAINT fk_chat_sessions_group_id__id FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE SET NULL ON UPDATE RESTRICT, CONSTRAINT fk_chat_sessions_current_model_id__id FOREIGN KEY (current_model_id) REFERENCES llm_models(id) ON DELETE SET NULL ON UPDATE RESTRICT, CONSTRAINT fk_chat_sessions_current_settings_id__id FOREIGN KEY (current_settings_id) REFERENCES model_settings(id) ON DELETE SET NULL ON UPDATE RESTRICT);
INSERT INTO chat_sessions VALUES(29,'Project Alpha Brainstorm',1754507807000,1754507807000,22,NULL,NULL);
INSERT INTO chat_sessions VALUES(30,'Recipe Ideas',1754507807100,1754507807100,23,NULL,NULL);
INSERT INTO chat_sessions VALUES(31,'Python Web Dev Help',1754507807200,1754508790842,24,NULL,NULL);
INSERT INTO chat_sessions VALUES(32,'General Office Chat',1754507807300,1754507807300,NULL,NULL,NULL);
INSERT INTO chat_sessions VALUES(33,'Multi-Topic Discussion',1754513456000,1754513533304,NULL,NULL,NULL);
INSERT INTO chat_sessions VALUES(34,'Technical Support Troubleshooting',1754513799000,1754953561502,NULL,NULL,NULL);
INSERT INTO chat_sessions VALUES(35,'New Chat - qwen3',1754955453652,1755550846438,NULL,1,1);
INSERT INTO chat_sessions VALUES(36,'New Chat - gemma3',1754962118523,1754966342899,NULL,2,2);
CREATE TABLE chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id BIGINT NOT NULL, "role" VARCHAR(50) NOT NULL, content TEXT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, parent_message_id BIGINT NULL, children_message_ids TEXT NOT NULL, CONSTRAINT fk_chat_messages_session_id__id FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_chat_messages_parent_message_id__id FOREIGN KEY (parent_message_id) REFERENCES chat_messages(id) ON DELETE SET NULL ON UPDATE RESTRICT);
INSERT INTO chat_messages VALUES(49,29,'USER','Hi team, let''s brainstorm some new features for Project Alpha. Any initial ideas?',1754507808000,1754507808000,NULL,'[50]');
INSERT INTO chat_messages VALUES(50,29,'ASSISTANT','I''ve analyzed user feedback. A key request is improved dashboard customization and more granular reporting options.',1754507809000,1754507809000,49,'[51]');
INSERT INTO chat_messages VALUES(51,29,'USER','Good point. How about integrating a collaborative whiteboarding tool directly into the project workspace?',1754507810000,1754507810000,50,'[52]');
INSERT INTO chat_messages VALUES(52,29,'ASSISTANT','That''s an interesting idea for enhancing collaboration. We could explore integrations with Miro or Mural APIs for real-time whiteboarding.',1754507811000,1754507811000,51,'[]');
INSERT INTO chat_messages VALUES(53,30,'USER','I need some inspiration for dinner tonight. Something quick and healthy.',1754507812000,1754507812000,NULL,'[54]');
INSERT INTO chat_messages VALUES(54,30,'ASSISTANT','How about a sheet pan lemon herb salmon with asparagus? It''s healthy, quick, and delicious!',1754507813000,1754507813000,53,'[55]');
INSERT INTO chat_messages VALUES(55,30,'USER','Sounds great! What ingredients do I need?',1754507814000,1754507814000,54,'[56]');
INSERT INTO chat_messages VALUES(56,30,'ASSISTANT','You''ll need salmon fillets, asparagus, lemons, olive oil, dried herbs (like dill, parsley, or oregano), salt, and pepper.',1754507815000,1754507815000,55,'[]');
INSERT INTO chat_messages VALUES(57,31,'USER','I''m building a web app with Flask and need help with database integration. What''s the best way to handle user authentication?',1754507816000,1754507816000,NULL,'[58]');
INSERT INTO chat_messages VALUES(58,31,'ASSISTANT','For Flask, Flask-Login is a popular and robust extension for managing user sessions, while Flask-WTF or Flask-Bcrypt can handle form validation and password hashing respectively.',1754507817000,1754507817000,57,'[59,61]');
INSERT INTO chat_messages VALUES(59,31,'USER','Thanks! And for the database, I''m using SQLite. Should I use SQLAlchemy or raw SQL?',1754507818000,1754507818000,58,'[60]');
INSERT INTO chat_messages VALUES(60,31,'ASSISTANT','SQLAlchemy is highly recommended. It provides an ORM (Object-Relational Mapper) that makes database interactions more Pythonic and less error-prone than raw SQL, especially for complex operations.',1754507819000,1754507819000,59,'[]');
INSERT INTO chat_messages VALUES(61,31,'USER','Regarding Flask-Login, is it difficult to integrate with OAuth providers like Google or GitHub?',1754507820000,1754507820000,58,'[62]');
INSERT INTO chat_messages VALUES(62,31,'ASSISTANT','Flask-Login provides the core session management, but for OAuth, you''d typically use another extension like Flask-Dance, which simplifies integration with popular OAuth providers.',1754507821000,1754507821000,61,'[]');
INSERT INTO chat_messages VALUES(63,32,'USER','Good morning, everyone. Quick question regarding the new office policy on remote work.',1754507822000,1754507822000,NULL,'[64]');
INSERT INTO chat_messages VALUES(64,32,'ASSISTANT','Good morning! The new policy aims for flexibility, allowing up to 3 days remote work per week. Details are in the updated HR portal document.',1754507823000,1754507823000,63,'[]');
INSERT INTO chat_messages VALUES(65,33,'USER','I''m planning a trip to Europe. Any tips for first-time travelers?',1754513457000,1754513457000,NULL,'[66]');
INSERT INTO chat_messages VALUES(66,33,'ASSISTANT','For first-time European travelers, focus on a few key cities rather than trying to see too much. Consider major hubs like Paris, Rome, or Barcelona.',1754513458000,1754513458000,65,'[67]');
INSERT INTO chat_messages VALUES(67,33,'USER','That''s helpful. What''s the best way to get around between cities?',1754513459000,1754513459000,66,'[68]');
INSERT INTO chat_messages VALUES(68,33,'ASSISTANT','Trains are excellent in Europe for inter-city travel. Look into Eurail passes if you plan multiple long journeys.',1754513460000,1754513460000,67,'[]');
INSERT INTO chat_messages VALUES(69,33,'USER','Separately, I''ve been thinking about getting into photography. What''s a good entry-level camera?',1754513461000,1754513461000,NULL,'[70]');
INSERT INTO chat_messages VALUES(70,33,'ASSISTANT','For beginners, a mirrorless camera like a Sony Alpha series or Fujifilm X-T is a great starting point, offering a good balance of portability and image quality.',1754513462000,1754513462000,69,'[71]');
INSERT INTO chat_messages VALUES(71,33,'USER','Thanks! Any recommendations for beginner-friendly editing software?',1754513463000,1754513463000,70,'[72]');
INSERT INTO chat_messages VALUES(72,33,'ASSISTANT','Lightroom is industry-standard, but for free options, GIMP or RawTherapee are powerful alternatives.',1754513464000,1754513464000,71,'[]');
INSERT INTO chat_messages VALUES(73,34,'USER','My software application is frequently crashing during startup. What should I check first?',1754513800000,1754513800000,NULL,'[74]');
INSERT INTO chat_messages VALUES(74,34,'ASSISTANT','Please provide the exact error message, and tell me your operating system and version. Also, have you tried reinstalling the application?',1754513801000,1754513801000,73,'[75,77]');
INSERT INTO chat_messages VALUES(75,34,'USER','The error message is "Application encountered an unhandled exception (0x0000005)".',1754513802000,1754513802000,74,'[76]');
INSERT INTO chat_messages VALUES(76,34,'ASSISTANT','Error 0x0000005 often indicates a memory access violation. Try running a memory diagnostic tool on your system.',1754513803000,1754513803000,75,'[]');
INSERT INTO chat_messages VALUES(77,34,'USER','I''m on Windows 10, version 22H2. Yes, I tried reinstalling, it didn''t help.',1754513804000,1754513804000,74,'[78]');
INSERT INTO chat_messages VALUES(78,34,'ASSISTANT','Given Windows 10 and a reinstallation didn''t work, let''s check your graphics drivers. Are they up to date?',1754513805000,1754513805000,77,'[79,81]');
INSERT INTO chat_messages VALUES(79,34,'USER','My graphics drivers are fully updated through Windows Update.',1754513806000,1754513806000,78,'[80]');
INSERT INTO chat_messages VALUES(80,34,'ASSISTANT','Windows Update drivers might not be the latest. Please download and install the drivers directly from your GPU manufacturer''s website (NVIDIA, AMD, or Intel).',1754513807000,1754513807000,79,'[]');
INSERT INTO chat_messages VALUES(81,34,'USER','Instead of drivers, could this be related to .NET Framework or Visual C++ Redistributables?',1754513808000,1754513808000,78,'[82]');
INSERT INTO chat_messages VALUES(82,34,'ASSISTANT','Yes, absolutely. Missing or corrupted runtime libraries like .NET or VC++ Redistributables are common causes for application crashes. You should try repairing/reinstalling those.',1754513809000,1754513809000,81,'[83]');
INSERT INTO chat_messages VALUES(83,34,'USER','Okay, I will prioritize checking the runtime libraries. Thanks for the detailed guidance!',1754513810000,1754513810000,82,'[]');
CREATE TABLE assistant_messages (message_id BIGINT NOT NULL PRIMARY KEY, model_id BIGINT NULL, settings_id BIGINT NULL, CONSTRAINT fk_assistant_messages_message_id__id FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_assistant_messages_model_id__id FOREIGN KEY (model_id) REFERENCES llm_models(id) ON DELETE SET NULL ON UPDATE RESTRICT, CONSTRAINT fk_assistant_messages_settings_id__id FOREIGN KEY (settings_id) REFERENCES model_settings(id) ON DELETE SET NULL ON UPDATE RESTRICT);
INSERT INTO assistant_messages VALUES(50,NULL,NULL);
INSERT INTO assistant_messages VALUES(52,NULL,NULL);
INSERT INTO assistant_messages VALUES(54,NULL,NULL);
INSERT INTO assistant_messages VALUES(56,NULL,NULL);
INSERT INTO assistant_messages VALUES(58,NULL,NULL);
INSERT INTO assistant_messages VALUES(60,NULL,NULL);
INSERT INTO assistant_messages VALUES(62,NULL,NULL);
INSERT INTO assistant_messages VALUES(64,NULL,NULL);
INSERT INTO assistant_messages VALUES(66,NULL,NULL);
INSERT INTO assistant_messages VALUES(68,NULL,NULL);
INSERT INTO assistant_messages VALUES(70,NULL,NULL);
INSERT INTO assistant_messages VALUES(72,NULL,NULL);
INSERT INTO assistant_messages VALUES(74,NULL,NULL);
INSERT INTO assistant_messages VALUES(76,NULL,NULL);
INSERT INTO assistant_messages VALUES(78,NULL,NULL);
INSERT INTO assistant_messages VALUES(80,NULL,NULL);
INSERT INTO assistant_messages VALUES(82,NULL,NULL);
CREATE TABLE session_current_leaf (session_id BIGINT NOT NULL PRIMARY KEY, message_id BIGINT NOT NULL, CONSTRAINT fk_session_current_leaf_session_id__id FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_session_current_leaf_message_id__id FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE ON UPDATE RESTRICT);
INSERT INTO session_current_leaf VALUES(29,52);
INSERT INTO session_current_leaf VALUES(30,56);
INSERT INTO session_current_leaf VALUES(31,60);
INSERT INTO session_current_leaf VALUES(32,64);
INSERT INTO session_current_leaf VALUES(33,72);
INSERT INTO session_current_leaf VALUES(34,80);
INSERT INTO sqlite_sequence VALUES('llm_providers',1);
INSERT INTO sqlite_sequence VALUES('llm_models',2);
INSERT INTO sqlite_sequence VALUES('model_settings',2);
INSERT INTO sqlite_sequence VALUES('chat_groups',24);
INSERT INTO sqlite_sequence VALUES('chat_sessions',36);
INSERT INTO sqlite_sequence VALUES('chat_messages',89);
INSERT INTO sqlite_sequence VALUES('chat_groups',24);
INSERT INTO sqlite_sequence VALUES('chat_sessions',36);
INSERT INTO sqlite_sequence VALUES('chat_messages',87);
INSERT INTO sqlite_sequence VALUES('llm_providers',1);
INSERT INTO sqlite_sequence VALUES('llm_models',2);
INSERT INTO sqlite_sequence VALUES('model_settings',2);
CREATE UNIQUE INDEX llm_providers_api_key_id ON llm_providers (api_key_id);
CREATE UNIQUE INDEX llm_models_name ON llm_models ("name");
CREATE INDEX model_settings_model_id ON model_settings (model_id);
CREATE INDEX chat_sessions_group_id ON chat_sessions (group_id);
CREATE INDEX chat_messages_session_id ON chat_messages (session_id);
CREATE INDEX chat_messages_parent_message_id ON chat_messages (parent_message_id);
CREATE UNIQUE INDEX session_current_leaf_message_id ON session_current_leaf (message_id);
COMMIT;
