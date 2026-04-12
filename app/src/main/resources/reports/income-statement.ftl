<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Resultatrapport</h2>
  <div class="metrics">
    <div class="metric">
      <span class="metric-label">Intäkter</span>
      <span class="metric-value">${incomeTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">Kostnader</span>
      <span class="metric-value">${expenseTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">Resultat</span>
      <span class="metric-value">${result}</span>
    </div>
  </div>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
