import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext"; 
import { API_BASE_URL } from "../config/api";
// import "../stylesheets/styles.scss";

function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const navigate = useNavigate();
  const { login } = useAuth(); 

  const handleLogin = async (e) => {
    e.preventDefault();
    const cleanEmail = email.trim().toLowerCase();
    const payload = { email: cleanEmail, password };
  
    try {
      const response = await fetch(`${API_BASE_URL}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      let data;
      const contentType = response.headers.get("content-type");
      if (contentType && contentType.includes("application/json")) {
        data = await response.json();
      } else {
        const text = await response.text();
        data = { message: text };
      }
  
      if (!response.ok) {
        alert("Login failed: " + (data.message || "Unknown error"));
        return;
      }
  
      localStorage.setItem("token", data.token);
      
      if (data.user) {
        localStorage.setItem("userData", JSON.stringify(data.user));
        login(data.user, data.token);
      } else {
        let userId = null;
        try {
          const payloadPart = JSON.parse(atob(data.token.split('.')[1]));
          userId = payloadPart.userId;
        } catch (err) {}
        const userData = { id: userId, email: cleanEmail, name: cleanEmail.split('@')[0] };
        localStorage.setItem("userData", JSON.stringify(userData));
        login(userData, data.token);
      }
      
      alert("Login successful!");
      navigate("/home");
  
    } catch (err) {
      console.error("Network error:", err);
      alert("Network error: " + err.message);
    }
  };

  return (
    <div className="auth-container">
      <div className="auth-card">
        <div className="auth-header">
          <h2>Welcome Back</h2>
          <p>Sign in to your account</p>
        </div>

        <form onSubmit={handleLogin}>
          <div className="form-group">
            <label htmlFor="email">Email Address</label>
            <input
              id="email"
              type="email"
              placeholder="Enter your email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="input-field"
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              placeholder="Enter your password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="input-field"
              required
            />
          </div>

          <button type="submit" className="submit-btn btn-login">
            Sign In
          </button>
        </form>

        <div className="auth-footer">
          <p>Don't have an account? <a href="/signup">Sign up</a></p>
        </div>
      </div>
    </div>
  );
}

export default LoginPage;