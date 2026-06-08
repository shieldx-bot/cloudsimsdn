import re

data_file = "/home/ac2006666/cloudsimsdn/dataset/01_a_train_data.txt"
label_file = "/home/ac2006666/cloudsimsdn/dataset/01_a_train_label.txt"

# Read first 1000 rows of data
rows = []
with open(data_file, 'r') as f:
    for i, line in enumerate(f, 1):
        if i > 1000:
            break
        line = line.strip()
        # Remove row number prefix (e.g., "1: ")
        parts = line.split(':', 1)
        if len(parts) == 2:
            values = parts[1].strip().split()
        else:
            values = parts[0].split()
        rows.append([float(v) for v in values])

n_cols = 33
n_rows = len(rows)
print(f"Total rows read: {n_rows}")
print(f"Columns per row: {len(rows[0])}")

# Compute statistics per column
stats = []
for col in range(n_cols):
    col_vals = [rows[row][col] for row in range(n_rows)]
    col_min = min(col_vals)
    col_max = max(col_vals)
    col_mean = sum(col_vals) / n_rows
    stats.append((col_min, col_max, col_mean))

# Print results
print("\nColumn Statistics (Min, Max, Mean) for first 1000 rows:")
print(f"{'Col':>4} {'Min':>14} {'Max':>14} {'Mean':>14}")
print("-" * 50)
for i, (mn, mx, mean) in enumerate(stats, 1):
    print(f"{i:>4} {mn:>14.6f} {mx:>14.6f} {mean:>14.6f}")

# Labels for rows 41-50
print("\n\nLabels for rows 41-50:")
with open(label_file, 'r') as f:
    for i, line in enumerate(f, 1):
        if 41 <= i <= 50:
            line = line.strip()
            parts = line.split(':', 1)
            val = parts[1].strip() if len(parts) == 2 else parts[0].strip()
            print(f"Row {i}: {val}")
