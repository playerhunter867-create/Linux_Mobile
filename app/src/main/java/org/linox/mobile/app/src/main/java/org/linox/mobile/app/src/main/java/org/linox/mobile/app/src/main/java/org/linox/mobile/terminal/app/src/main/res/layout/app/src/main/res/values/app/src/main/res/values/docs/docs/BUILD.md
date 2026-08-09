# Building LinOx Mobile

## Option A — GitHub Actions (recommended, no local Android setup needed)

1. Push this project to your GitHub repo (root of the repo should be this
   `linox07` folder's contents — i.e. `build.gradle.kts` and `app/` sit at
   the repo root, not nested one level down).

   ```bash
   cd linox07
   git init
   git add .
   git commit -m "LinOx Mobile v0.9.0"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
