package com.example.splitease.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Additive migrations: v1 accounting, v2 recurring bills, v3 original creator attribution. */
public final class SplitEaseDatabase extends SQLiteOpenHelper {
    public static final int VERSION = 3;
    public SplitEaseDatabase(Context context) { this(context, "splitease.db", VERSION); }
    public SplitEaseDatabase(Context context, String name) { this(context, name, VERSION); }
    // Package-visible version selection permits a real v1 -> v2 migration test.
    SplitEaseDatabase(Context context, String name, int version) {
        super(context.getApplicationContext(), name, null, version);
        targetVersion = version;
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        createCore(db);
        if (targetVersion >= 2) createRecurring(db);
        if (targetVersion >= 3) addCreator(db);
    }
    private final int targetVersion;
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || newVersion > VERSION || oldVersion >= newVersion)
            throw new IllegalStateException("Unsupported migration " + oldVersion + " -> " + newVersion);
        if (oldVersion < 2 && newVersion >= 2) createRecurring(db);
        if (oldVersion < 3 && newVersion >= 3) addCreator(db);
    }
    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Database downgrade would risk data loss");
    }
    private static void addCreator(SQLiteDatabase db) {
        // SQLite permits adding a nullable FK column while foreign keys are enabled.
        db.execSQL("ALTER TABLE groups ADD COLUMN creator_id TEXT REFERENCES users(id)");
        db.execSQL("UPDATE groups SET creator_id=admin_id");
        db.execSQL("CREATE TRIGGER groups_creator_required_insert BEFORE INSERT ON groups WHEN NEW.creator_id IS NULL BEGIN SELECT RAISE(ABORT,'creator required'); END");
        db.execSQL("CREATE TRIGGER groups_creator_immutable BEFORE UPDATE OF creator_id ON groups WHEN NEW.creator_id IS NULL OR NEW.creator_id<>OLD.creator_id BEGIN SELECT RAISE(ABORT,'creator immutable'); END");
    }
    private static void createCore(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE users (id TEXT PRIMARY KEY NOT NULL, username TEXT NOT NULL COLLATE NOCASE UNIQUE, email TEXT NOT NULL COLLATE NOCASE UNIQUE, upi_id TEXT, password_hash BLOB NOT NULL, password_salt BLOB NOT NULL, password_algorithm TEXT NOT NULL, password_iterations INTEGER NOT NULL, password_memory INTEGER NOT NULL, password_parallelism INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE groups (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL CHECK(length(trim(name))>0), type TEXT NOT NULL CHECK(type IN ('FLAT','TRIP')), admin_id TEXT NOT NULL REFERENCES users(id), created_at INTEGER NOT NULL CHECK(created_at>=0))");
        db.execSQL("CREATE TABLE group_members (group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id), status TEXT NOT NULL CHECK(status IN ('ACTIVE','LEFT')), joined_at INTEGER NOT NULL CHECK(joined_at>=0), left_at INTEGER, PRIMARY KEY(group_id,user_id), CHECK((status='ACTIVE' AND left_at IS NULL) OR (status='LEFT' AND left_at IS NOT NULL AND left_at>=joined_at)))");
        db.execSQL("CREATE INDEX members_user ON group_members(user_id,status)");
        db.execSQL("CREATE TABLE expenses (id TEXT PRIMARY KEY NOT NULL, group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE, title TEXT NOT NULL, amount_paise INTEGER NOT NULL CHECK(typeof(amount_paise)='integer' AND amount_paise>0), payer_id TEXT NOT NULL, creator_id TEXT NOT NULL, category TEXT NOT NULL, expense_date INTEGER NOT NULL CHECK(expense_date>=0), UNIQUE(id,group_id), FOREIGN KEY(group_id,payer_id) REFERENCES group_members(group_id,user_id), FOREIGN KEY(group_id,creator_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE INDEX expenses_group_date ON expenses(group_id,expense_date)");
        db.execSQL("CREATE TABLE expense_splits (expense_id TEXT NOT NULL, group_id TEXT NOT NULL, user_id TEXT NOT NULL, share_paise INTEGER NOT NULL CHECK(typeof(share_paise)='integer' AND share_paise>=0), PRIMARY KEY(expense_id,user_id), FOREIGN KEY(expense_id,group_id) REFERENCES expenses(id,group_id) ON DELETE CASCADE, FOREIGN KEY(group_id,user_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE INDEX splits_user ON expense_splits(group_id,user_id)");
        db.execSQL("CREATE TABLE settlements (id TEXT PRIMARY KEY NOT NULL, group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE, payer_id TEXT NOT NULL, payee_id TEXT NOT NULL, amount_paise INTEGER NOT NULL CHECK(typeof(amount_paise)='integer' AND amount_paise>0), settlement_date INTEGER NOT NULL CHECK(settlement_date>=0), CHECK(payer_id<>payee_id), FOREIGN KEY(group_id,payer_id) REFERENCES group_members(group_id,user_id), FOREIGN KEY(group_id,payee_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE INDEX settlements_group_date ON settlements(group_id,settlement_date)");
    }
    private static void createRecurring(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recurring_bills (id TEXT PRIMARY KEY NOT NULL, group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE, title TEXT NOT NULL, amount_paise INTEGER NOT NULL CHECK(typeof(amount_paise)='integer' AND amount_paise>0), due_day INTEGER NOT NULL CHECK(due_day BETWEEN 1 AND 31), handler_id TEXT, reminder_lead_days INTEGER NOT NULL CHECK(reminder_lead_days>=0), active INTEGER NOT NULL CHECK(active IN (0,1)), UNIQUE(id,group_id), FOREIGN KEY(group_id,handler_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE INDEX bills_group ON recurring_bills(group_id,active)");
        db.execSQL("CREATE TABLE recurring_participants (bill_id TEXT NOT NULL, group_id TEXT NOT NULL, user_id TEXT NOT NULL, PRIMARY KEY(bill_id,user_id), FOREIGN KEY(bill_id,group_id) REFERENCES recurring_bills(id,group_id) ON DELETE CASCADE, FOREIGN KEY(group_id,user_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE TABLE recurring_cycles (id TEXT PRIMARY KEY NOT NULL, bill_id TEXT NOT NULL, group_id TEXT NOT NULL, year INTEGER NOT NULL CHECK(year BETWEEN 1970 AND 9999), month INTEGER NOT NULL CHECK(month BETWEEN 1 AND 12), due_date INTEGER NOT NULL CHECK(due_date>=0), amount_paise INTEGER NOT NULL CHECK(typeof(amount_paise)='integer' AND amount_paise>0), handler_id TEXT, UNIQUE(bill_id,year,month), UNIQUE(id,group_id), FOREIGN KEY(bill_id,group_id) REFERENCES recurring_bills(id,group_id), FOREIGN KEY(group_id,handler_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE TABLE recurring_contributions (cycle_id TEXT NOT NULL, group_id TEXT NOT NULL, user_id TEXT NOT NULL, share_paise INTEGER NOT NULL CHECK(typeof(share_paise)='integer' AND share_paise>=0), paid INTEGER NOT NULL DEFAULT 0 CHECK(paid IN (0,1)), PRIMARY KEY(cycle_id,user_id), FOREIGN KEY(cycle_id,group_id) REFERENCES recurring_cycles(id,group_id) ON DELETE CASCADE, FOREIGN KEY(group_id,user_id) REFERENCES group_members(group_id,user_id))");
        db.execSQL("CREATE INDEX contributions_user ON recurring_contributions(user_id,paid)");
    }
}
