// App.js
import { BrowserRouter as Router, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext"; // ADD THIS
import ProtectedRoute from "./components/ProtectedRoute"; // ADD THIS
import SignupPage from "./views/SignupPage";
import LoginPage from "./views/LoginPage";
import Dashboard from "./views/Dashboard";
import SendMoney from "./views/SendMoney";
import AddFunds from "./views/AddFunds";
import Rewards from "./views/Rewards";
import TransactionPage from "./views/TransactionPage";
import Layout from "./components/Layout"; 
import HomePage from "./views/Home";
import MyProfile from "./views/MyProfile";

function App() {
  return (
    <AuthProvider> {/* WRAP WITH THIS */}
      <Router>
        <Routes>
          {/* Public routes without layout */}
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<HomePage />} />
          
          {/* Protected routes with layout - JUST WRAP EACH WITH ProtectedRoute */}
          <Route 
            path="/home" 
            element={
              <ProtectedRoute> {/* ADD THIS */}
                <Dashboard />
              </ProtectedRoute> /* ADD THIS */
            } 
          /> 
          
          <Route 
            path="/send-money" 
            element={
              <ProtectedRoute> {/* ADD THIS */}
                <Layout 
                  showBackButton={true}
                  showQuickActions={true}
                >
                  <SendMoney />
                </Layout>
              </ProtectedRoute> /* ADD THIS */
            } 
          /> 

          <Route 
            path="/profile" 
            element={
              <ProtectedRoute> {/* ADD THIS */}
                <Layout 
                  title="My Profile"
                  subtitle="Manage your account settings"
                  showBackButton={true}
                  showQuickActions={true}
                >
                  <MyProfile />
                </Layout>
              </ProtectedRoute> /* ADD THIS */
            } 
          />
          
          <Route 
            path="/add-funds" 
            element={
              <ProtectedRoute> {/* ADD THIS */}
                <Layout 
                  showBackButton={true}
                  showQuickActions={true}
                >
                  <AddFunds />
                </Layout>
              </ProtectedRoute> /* ADD THIS */
            } 
          /> 
          
          <Route 
            path="/rewards" 
            element={
              <ProtectedRoute> {/* ADD THIS */}
                <Layout 
                  showBackButton={true}
                  showQuickActions={true}
                >
                  <Rewards />
                </Layout>
              </ProtectedRoute> /* ADD THIS */
            } 
          /> 
          
          <Route 
            path="/transactions" 
            element={
              <ProtectedRoute> {/* ADD THIS */}
                <Layout 
                  showBackButton={true}
                  showQuickActions={true}
                >
                  <TransactionPage />
                </Layout>
              </ProtectedRoute> /* ADD THIS */
            } 
          /> 
        </Routes>
      </Router>
    </AuthProvider> /* ADD THIS */
  );
}

export default App;