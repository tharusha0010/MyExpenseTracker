package com.tharusha.myexpensetracker

package com.tharusha.myexpensetracker

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // Database and UI component declarations
    private lateinit var db: AppDatabase
    private lateinit var llGroupContainer: LinearLayout
    private lateinit var spinnerGroupMain: Spinner
    private var groupList: List<ExpenseGroup> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize the local database instance
        db = AppDatabase.getDatabase(this)

        // Link UI components for displaying groups
        llGroupContainer = findViewById(R.id.llGroupContainer)
        spinnerGroupMain = findViewById(R.id.spinnerGroupMain)

        // Link UI components for adding a new group
        val etNewGroup = findViewById<EditText>(R.id.etNewGroup)
        val btnAddGroup = findViewById<Button>(R.id.btnAddGroup)

        // Link UI components for adding a new main expense
        val etTitleMain = findViewById<TextInputEditText>(R.id.etTitleMain)
        val etAmountMain = findViewById<TextInputEditText>(R.id.etAmountMain)
        val etDateMain = findViewById<TextInputEditText>(R.id.etDateMain)
        val etCategoryMain = findViewById<TextInputEditText>(R.id.etCategoryMain)
        val btnAddExpenseMain = findViewById<Button>(R.id.btnAddExpenseMain)

        // Link search bar
        val etSearchMain = findViewById<TextInputEditText>(R.id.etSearchMain)

        // Link cloud backup and restore buttons
        val btnBackupCloud = findViewById<Button>(R.id.btnBackupCloud)
        val btnRestoreCloud = findViewById<Button>(R.id.btnRestoreCloud)

        // Handle Add Expense Button Click
        btnAddExpenseMain.setOnClickListener {
            val title = etTitleMain.text.toString()
            val amount = etAmountMain.text.toString().toDoubleOrNull() ?: 0.0
            val date = etDateMain.text.toString()
            val category = etCategoryMain.text.toString()

            // Check if a valid group is selected from the spinner
            val selectedPosition = spinnerGroupMain.selectedItemPosition
            if (selectedPosition < 0 || groupList.isEmpty()) {
                Toast.makeText(this, "Please select a group first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedGroupId = groupList[selectedPosition].groupId

            // Validate inputs and save to local database
            if (title.isNotEmpty() && amount > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.expenseDao().insertExpense(
                        Expense(groupId = selectedGroupId, title = title, amount = amount, date = date, category = category)
                    )
                    // Clear text fields and update UI after successful save
                    withContext(Dispatchers.Main) {
                        etTitleMain.text?.clear()
                        etAmountMain.text?.clear()
                        etDateMain.text?.clear()
                        etCategoryMain.text?.clear()
                        Toast.makeText(this@MainActivity, "Expense Added!", Toast.LENGTH_SHORT).show()
                        loadGroups()
                    }
                }
            } else {
                Toast.makeText(this, "Please fill Title and Amount", Toast.LENGTH_SHORT).show()
            }
        }

        // Handle Add Group Button Click
        btnAddGroup.setOnClickListener {
            val groupName = etNewGroup.text.toString()

            // Proceed only if the group name is not empty
            if (groupName.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.expenseDao().insertGroup(ExpenseGroup(groupName = groupName))

                    // Clear the input and reload the group list
                    withContext(Dispatchers.Main) {
                        etNewGroup.text.clear()
                        loadGroups()
                    }
                }
            } else {
                Toast.makeText(this, "Please enter a group name", Toast.LENGTH_SHORT).show()
            }
        }

        // Listen for typing in the search bar to filter groups
        etSearchMain.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                displayGroups(query)
            }
        })

        // Cloud Backup logic using Firebase Firestore
        btnBackupCloud?.setOnClickListener {
            Toast.makeText(this, "Starting Backup...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO) {
                // Fetch all data from local Room database
                val allGroups = db.expenseDao().getAllGroups()
                val allExpenses = db.expenseDao().getAllExpenses()

                // Prepare data map for Firebase upload
                val backupMap = hashMapOf(
                    "groups" to allGroups,
                    "expenses" to allExpenses
                )

                // Upload to Firebase
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("CloudBackups").document("User_Database_Backup")
                    .set(backupMap)
                    .addOnSuccessListener {
                        Toast.makeText(this@MainActivity, "Backup Successful!", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this@MainActivity, "Backup Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }

        // Cloud Restore logic using Firebase Firestore
        btnRestoreCloud?.setOnClickListener {
            Toast.makeText(this, "Restoring from Cloud...", Toast.LENGTH_SHORT).show()
            val firestore = FirebaseFirestore.getInstance()

            // Fetch data from Firebase
            firestore.collection("CloudBackups").document("User_Database_Backup").get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val groupsData = document.get("groups") as? List<HashMap<String, Any>> ?: emptyList()
                                val expensesData = document.get("expenses") as? List<HashMap<String, Any>> ?: emptyList()

                                // Clear current local data before restoring new data
                                db.expenseDao().deleteAllExpenses()
                                db.expenseDao().deleteAllGroups()

                                // Restore Groups to local database
                                for (g in groupsData) {
                                    val newGroup = ExpenseGroup(
                                        groupId = (g["groupId"] as Number).toInt(),
                                        groupName = g["groupName"] as String
                                    )
                                    db.expenseDao().insertGroup(newGroup)
                                }

                                // Restore Expenses to local database
                                for (e in expensesData) {
                                    val newExpense = Expense(
                                        id = (e["id"] as Number).toInt(),
                                        groupId = (e["groupId"] as Number).toInt(),
                                        title = e["title"] as String,
                                        amount = (e["amount"] as Number).toDouble(),
                                        date = e["date"] as String,
                                        category = e["category"] as String
                                    )
                                    db.expenseDao().insertExpense(newExpense)
                                }

                                // Update UI after successful restore
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Restore Completely Successful!", Toast.LENGTH_SHORT).show()
                                    loadGroups()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Restore Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "No backup found in the cloud.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this@MainActivity, "Connection Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload groups every time the activity comes to the foreground
        loadGroups()
    }

    // Function to load existing groups from the local database
    private fun loadGroups() {
        lifecycleScope.launch(Dispatchers.IO) {

            // Add default template groups if the database is completely empty
            if (db.expenseDao().getAllGroups().isEmpty()) {
                db.expenseDao().insertGroup(ExpenseGroup(groupName = "Daily Expenses"))
                db.expenseDao().insertGroup(ExpenseGroup(groupName = "Monthly Budget"))
                db.expenseDao().insertGroup(ExpenseGroup(groupName = "Trip Expenses"))
            }

            groupList = db.expenseDao().getAllGroups()

            // Update Spinner and Group list on the Main thread
            withContext(Dispatchers.Main) {
                val groupNames = groupList.map { it.groupName }
                val spinnerAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, groupNames)
                spinnerGroupMain.adapter = spinnerAdapter
                displayGroups("") // Show all groups initially
            }
        }
    }

    // Function to display groups dynamically in the UI (filtered by search query)
    private fun displayGroups(searchQuery: String) {
        // Clear previous views to prevent duplicates
        llGroupContainer.removeAllViews()

        // Filter groups based on the search query
        val filteredGroups = if (searchQuery.isEmpty()) {
            groupList
        } else {
            groupList.filter { it.groupName.contains(searchQuery, ignoreCase = true) }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (group in filteredGroups) {
                // Get the total number of expenses for each specific group
                val count = db.expenseDao().getExpenseCountByGroup(group.groupId)

                withContext(Dispatchers.Main) {
                    // Create a button dynamically for each group
                    val btn = Button(this@MainActivity)
                    btn.text = "${group.groupName} ($count)"
                    btn.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        150
                    ).apply { setMargins(0, 0, 0, 16) }

                    // Navigate to ExpenseListActivity when a group button is clicked
                    btn.setOnClickListener {
                        val intent = Intent(this@MainActivity, ExpenseListActivity::class.java)
                        intent.putExtra("GROUP_ID", group.groupId)
                        intent.putExtra("GROUP_NAME", group.groupName)
                        startActivity(intent)
                    }
                    // Add the created button to the LinearLayout container
                    llGroupContainer.addView(btn)
                }
            }
        }
    }
}